import { App } from 'aws-cdk-lib'
import { LoanerStack } from '../lib/loaner-stack.ts'

const app = new App()
new LoanerStack(app, 'DevkitLoaner', { env: { region: 'us-west-2' } })
