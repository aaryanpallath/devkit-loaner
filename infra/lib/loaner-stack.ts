import * as path from 'node:path'
import { fileURLToPath } from 'node:url'
import { CfnOutput, Duration, RemovalPolicy, Stack, type StackProps } from 'aws-cdk-lib'
import * as cloudfront from 'aws-cdk-lib/aws-cloudfront'
import * as origins from 'aws-cdk-lib/aws-cloudfront-origins'
import * as docdb from 'aws-cdk-lib/aws-docdb'
import * as ec2 from 'aws-cdk-lib/aws-ec2'
import * as ecs from 'aws-cdk-lib/aws-ecs'
import * as patterns from 'aws-cdk-lib/aws-ecs-patterns'
import * as events from 'aws-cdk-lib/aws-events'
import * as targets from 'aws-cdk-lib/aws-events-targets'
import * as lambda from 'aws-cdk-lib/aws-lambda'
import * as s3 from 'aws-cdk-lib/aws-s3'
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager'
import type { Construct } from 'constructs'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..')

/**
 * API on ECS Fargate behind an internal ALB, DocumentDB (MongoDB-compatible) in private subnets, the overdue-reminder
 * Lambda inside the VPC on a daily EventBridge schedule, and the React app on S3 + CloudFront, which reaches the
 * internal ALB for /api/* through a CloudFront VPC origin. Nothing but CloudFront is reachable from the internet.
 *
 * Deploy-time setting: -c oidcJwksUri=<company IdP JWKS URL>, used to verify sign-in tokens.
 */
export class LoanerStack extends Stack {
  constructor(scope: Construct, id: string, props?: StackProps) {
    super(scope, id, props)

    const vpc = new ec2.Vpc(this, 'Vpc', { maxAzs: 2, natGateways: 1 })

    const db = new docdb.DatabaseCluster(this, 'Db', {
      vpc,
      masterUser: { username: 'loaner' },            // password generated into Secrets Manager
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.T4G, ec2.InstanceSize.MEDIUM),
      instances: 1,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      storageEncrypted: true,
      removalPolicy: RemovalPolicy.SNAPSHOT,
    })

    const jwksUri: string = this.node.tryGetContext('oidcJwksUri') ?? 'https://login.example.com/.well-known/jwks.json'

    const cluster = new ecs.Cluster(this, 'Cluster', { vpc, containerInsightsV2: ecs.ContainerInsights.ENABLED })
    const api = new patterns.ApplicationLoadBalancedFargateService(this, 'Api', {
      cluster,
      publicLoadBalancer: false,      // internal: no public address
      openListener: false,            // no 0.0.0.0/0 rule; access is granted to the VPC below
      cpu: 512,
      memoryLimitMiB: 1024,
      desiredCount: 2,
      taskImageOptions: {
        image: ecs.ContainerImage.fromAsset(path.join(root, 'api')),
        containerPort: 8080,
        environment: {
          // the "aws" Spring profile builds the DocumentDB connection string from these (application-aws.properties)
          SPRING_PROFILES_ACTIVE: 'aws',
          OIDC_JWKS_URI: jwksUri,
          DOCDB_HOST: db.clusterEndpoint.hostname,
          DOCDB_PORT: db.clusterEndpoint.portAsString(),
        },
        secrets: {
          DOCDB_USER: ecs.Secret.fromSecretsManager(db.secret!, 'username'),
          DOCDB_PASSWORD: ecs.Secret.fromSecretsManager(db.secret!, 'password'),
        },
      },
      minHealthyPercent: 100,
      circuitBreaker: { rollback: true },
    })
    api.targetGroup.configureHealthCheck({ path: '/actuator/health' })
    // CloudFront VPC-origin endpoints and the Lambda both live in this VPC; nothing outside it can connect.
    api.loadBalancer.connections.allowFrom(ec2.Peer.ipv4(vpc.vpcCidrBlock), ec2.Port.tcp(80), 'from inside the VPC')
    db.connections.allowDefaultPortFrom(api.service)

    // REMINDER_SERVICE token for the Lambda, issued by the IdP's client-credentials flow and stored here
    const reminderToken = new secretsmanager.Secret(this, 'ReminderServiceToken', {
      description: 'Bearer token with the REMINDER_SERVICE role for the overdue-reminder Lambda',
    })
    const reminder = new lambda.Function(this, 'OverdueReminder', {
      runtime: lambda.Runtime.PYTHON_3_12,
      vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      handler: 'overdue_reminder.handler.handler',
      code: lambda.Code.fromAsset(path.join(root, 'lambda'), { exclude: ['tests', '**/__pycache__'] }),
      timeout: Duration.seconds(30),
      environment: {
        API_URL: `http://${api.loadBalancer.loadBalancerDnsName}`,   // internal ALB, private network only
        TOKEN_SECRET_ARN: reminderToken.secretArn,
      },
    })
    reminderToken.grantRead(reminder)
    new events.Rule(this, 'DailyReminders', {
      schedule: events.Schedule.cron({ minute: '0', hour: '16' }),   // 9:00 Pacific (PDT)
      targets: [new targets.LambdaFunction(reminder)],
    })

    const site = new s3.Bucket(this, 'Site', {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      enforceSSL: true,
      removalPolicy: RemovalPolicy.DESTROY,
      autoDeleteObjects: true,
    })
    const cdn = new cloudfront.Distribution(this, 'Cdn', {
      defaultRootObject: 'index.html',
      defaultBehavior: {
        origin: origins.S3BucketOrigin.withOriginAccessControl(site),
        viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
      },
      additionalBehaviors: {
        '/api/*': {
          // private path from CloudFront into the VPC; viewers always use HTTPS to CloudFront
          origin: origins.VpcOrigin.withApplicationLoadBalancer(api.loadBalancer, {
            protocolPolicy: cloudfront.OriginProtocolPolicy.HTTP_ONLY,
          }),
          allowedMethods: cloudfront.AllowedMethods.ALLOW_ALL,
          cachePolicy: cloudfront.CachePolicy.CACHING_DISABLED,
          originRequestPolicy: cloudfront.OriginRequestPolicy.ALL_VIEWER_EXCEPT_HOST_HEADER,
          viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
        },
      },
    })

    new CfnOutput(this, 'SiteUrl', { value: `https://${cdn.distributionDomainName}` })
    new CfnOutput(this, 'SiteBucket', { value: site.bucketName })
    new CfnOutput(this, 'InternalApiUrl', { value: `http://${api.loadBalancer.loadBalancerDnsName}` })
    new CfnOutput(this, 'ReminderTokenSecret', { value: reminderToken.secretArn })
  }
}
