import assert from 'node:assert/strict'
import { test } from 'node:test'
import { App } from 'aws-cdk-lib'
import { Match, Template } from 'aws-cdk-lib/assertions'
import { LoanerStack } from '../lib/loaner-stack.ts'

const template = Template.fromStack(new LoanerStack(new App(), 'Test', { env: { region: 'us-west-2' } }))

test('DocumentDB is encrypted and its password lives in Secrets Manager', () => {
  template.hasResourceProperties('AWS::DocDB::DBCluster', { StorageEncrypted: true })
  template.resourceCountIs('AWS::SecretsManager::Secret', 2)    // DocumentDB master + reminder service token
})

test('the API runs on Fargate with secrets injected, not plain environment values', () => {
  template.hasResourceProperties('AWS::ECS::Service', { LaunchType: 'FARGATE', DesiredCount: 2 })
  const td = template.findResources('AWS::ECS::TaskDefinition')
  const container = Object.values(td)[0].Properties.ContainerDefinitions[0]
  const secretNames = container.Secrets.map((s: { Name: string }) => s.Name).sort()
  assert.deepEqual(secretNames, ['DOCDB_PASSWORD', 'DOCDB_USER'])
  const envNames = container.Environment.map((e: { Name: string }) => e.Name)
  assert.ok(!envNames.includes('DOCDB_PASSWORD'))
  assert.ok(envNames.includes('SPRING_PROFILES_ACTIVE'))
  for (const name of ['OIDC_JWKS_URI', 'OIDC_ISSUER', 'OIDC_AUDIENCE']) assert.ok(envNames.includes(name), name)
})

test('only the API service can reach the database port', () => {
  const rules = Object.values(template.findResources('AWS::EC2::SecurityGroupIngress'))
    .map((r) => r.Properties)
    .filter((p) => JSON.stringify(p.GroupId).includes('DbSecurityGroup'))
  assert.equal(rules.length, 1)
  assert.match(JSON.stringify(rules[0].SourceSecurityGroupId), /ApiServiceSecurityGroup/)
  assert.match(JSON.stringify(rules[0].FromPort), /"Port"/)        // the cluster's own port attribute
  assert.equal(rules[0].CidrIp, undefined)                          // never open to an address range
})

test('the load balancer is internal and not open to the internet', () => {
  template.hasResourceProperties('AWS::ElasticLoadBalancingV2::LoadBalancer', { Scheme: 'internal' })
  // every inbound rule, whether inline on a security group or a separate ingress resource
  const inbound = [
    ...Object.values(template.findResources('AWS::EC2::SecurityGroup'))
      .flatMap((g) => g.Properties.SecurityGroupIngress ?? []),
    ...Object.values(template.findResources('AWS::EC2::SecurityGroupIngress')).map((r) => r.Properties),
  ]
  assert.ok(inbound.length > 0)
  assert.ok(inbound.every((r) => r.CidrIp !== '0.0.0.0/0' && r.CidrIpv6 !== '::/0'), JSON.stringify(inbound))
  template.resourceCountIs('AWS::CloudFront::VpcOrigin', 1)
})

test('the reminder Lambda runs daily inside the VPC with its token in Secrets Manager', () => {
  template.hasResourceProperties('AWS::Lambda::Function', {
    Runtime: 'python3.12', Handler: 'overdue_reminder.handler.handler',
    VpcConfig: Match.objectLike({ SubnetIds: Match.anyValue() }),
    Environment: { Variables: { API_URL: Match.anyValue(), TOKEN_SECRET_ARN: Match.anyValue() } },
  })
  template.hasResourceProperties('AWS::Events::Rule', { ScheduleExpression: 'cron(0 16 * * ? *)' })
})

test('the site bucket is private and served over HTTPS through CloudFront', () => {
  template.hasResourceProperties('AWS::S3::Bucket', {
    PublicAccessBlockConfiguration: { BlockPublicAcls: true, BlockPublicPolicy: true },
  })
  template.hasResourceProperties('AWS::CloudFront::Distribution', Match.objectLike({
    DistributionConfig: Match.objectLike({
      DefaultCacheBehavior: Match.objectLike({ ViewerProtocolPolicy: 'redirect-to-https' }),
    }),
  }))
})
