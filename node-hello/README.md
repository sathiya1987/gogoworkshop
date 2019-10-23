# node-hello
A simple hello world node js application


# Running the app
>npm start


The repository consists of a set of nested templates that deploy the following:

A highly available ECS cluster deployed across two Availability Zones in an Auto Scaling group and that are AWS SSM enabled.

Two micro services deployed as ECS services (traefick and clair).

A database instance RDS Aurora for clair registry vulnerabilities scanner.

An Application Load Balancer (ALB) to traefik service.

Centralized container logging with Amazon CloudWatch Logs.

Cloudwatch Alarms for all services to monitor critical metrics CloudWatch Monitoring

Usage / Notes
ALB HTTPS Certificate needs to be uploaded prior to stack being deployed
Check if EC2 Keypair is available
Operating Systems
The following OS options are used: -

Amazon ECS Optimized AMI
AWS Regions
Configured for: -

all (no hardcoded AMI's)
Todos
Last Changed:
Last Updated by:Sathiyaraj
