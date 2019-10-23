// 0.cicd-nonprod-nodejs-app
// Build and deploy a feature branch of the "node-hello" application in the DEV and QA ECS
//
// branch: feature  -- Name of the branch to build and test in the Non-Prod environment
// port: 8080       -- The port the application is running on (used for traefik configuration)

def title(cmd) {
    sh('#!/bin/sh -e\necho -e "\\e[1m\\e[34m' + cmd + '\\e[39m\\e[0m"')
}

def warn(cmd) {
    sh('#!/bin/sh -e\necho -e "\\e[1m\\e[35m' + cmd + '\\e[39m\\e[0m"')
}

def result(cmd) {
    sh('#!/bin/sh -e\necho -e "\\e[1m\\e[32m' + cmd + '\\e[39m\\e[0m"')
} 

pipeline {

    options {
        buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '15')
        disableConcurrentBuilds()
    }

    agent none

    environment {
        jenkinsCredentialsForBitbucket = "git-user-creds"
        repository = "http://.git"
        registry = "https://${NONPROD}.dkr.ecr.${REGION}.amazonaws.com"
        imageName = "nodeapp-dev"
        serviceName = "frontend"
        businessDomain = "gogo"
        projectKey = credentials('projectKey')
		sonarqubeurl = credentials('sonarqubeurl')
		projectname = credentials('projectname')
		projecttoken = credentials('projecttoken')
		loginusersonar = credentials('LOGINUSERSONAR')
		userpasswordsonar = credentials('USERPASSWORDSONAR')
    }

    stages {
        stage("Clone Git") {
            agent any
            steps {
                title "Clone the Bitbucket repository"
                script {
                    def scmVars = checkout([$class: 'GitSCM', branches: [[name: "$branch"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CloneOption', noTags: false, reference: '', shallow: true]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: jenkinsCredentialsForBitbucket, url: repository]]])
                    env.GIT_COMMIT = scmVars.GIT_COMMIT.substring(0, 8)
                }

                title "Check if image already exists in the registry"
                script {
                    env.ALREADY_EXISTS = sh (
                        script: """
                            eval \$(aws ecr get-login --region $REGION --no-include-email)
                            aws ecr list-images \
                                --region eu-west-1 \
                                --repository-name $imageName \
                            | jq -r '.imageIds[].imageTag' \
                            | grep "d-${env.GIT_COMMIT}"
                        """,
                        returnStatus: true
                    ) == 0
                }
            }
        }
        stage('LINT') {
            steps {
                echo '**************** START - NPM lint stage pipeline ****************'
                sh 'ng lint'
				echo '**************** END - NPM lint stage pipeline ****************'
                }
           }

		stage('TEST') {
            steps {
                echo '**************** START - NPM Test stag pipeline ****************'
                sh 'ng test --watch=false --code-coverage'
				echo '**************** END - NPM Test stag pipeline ****************'
                }
           }
        stage('CODE ANALYSIS') {
            steps {
                echo '**************** START - SonarQube pipeline ****************'
                sh 'npm install -g sonarqube-scanner -unsafe-perm --allow-root'
                sh ' sonar-scanner \
                                 -Dsonar.projectKey=${projectKey} \
                                 -Dsonar.host.url=${sonarqubeurl} \
                                 -Dsonar.login=${projecttoken} \
                                 -Dsonar.projectName=${projectname} \
                                 -Dsonar.sourceEncoding=UTF-8 \
                                 -Dsonar.sources=src \
                                 -Dsonarr.exclusions=**/node_modules/**,**/*.spec.ts \
                                 -Dsonar.tests=src \
                                 -Dsonar.test.inclusions=**/*.spec.ts \
                                 -Dsonar.typescript.lcov.reportPaths=coverage/Setup/lcov.info '
                script {
                  sleep 10
                  sh "curl -u  ${loginusersonar}:${userpasswordsonar} -X GET -H 'Accept: application/json' https://${sonarqubeurl}/api/qualitygates/project_status?projectKey=${projectKey}> status.json"
                  def json = readJSON file:'status.json'
                  echo "${json.projectStatus.status}"
                  if ("${json.projectStatus.status}" != "OK") {
                             currentBuild.result = 'FAILURE'
                  error "Pipeline aborted due to quality gate failure: ${json.projectStatus.status}"
				   } }
				echo '**************** END SonarQube Analysis Stage pipeline ****************'
                }
           }
        stage("Build") {
            agent any
            when { not { expression { return env.ALREADY_EXISTS.toBoolean() } } }
            steps {
                title "Test and Build the executable"
                sh "npm install"
            }
        }
        stage("Docker Image") {
            steps {
                title "Build and tag the Docker Image"
                script {
                    sh "pwd"
                    sh "ls -l"
                    dir("./") {
                    docker.build(imageName, "-f DockerFile .")
                    }
                    
                }
            }
        }
        stage("Push to Registry") {
            agent any
            when { not { expression { return env.ALREADY_EXISTS.toBoolean() } } }
            steps {
                script {
                    title "Push the Docker image to the registry"
                    sh "eval \$(aws ecr get-login --region $REGION --no-include-email)"
                    docker.withRegistry(registry) {
                        docker.image(imageName).push("d-${env.GIT_COMMIT}")
                    }
                    sh "aws ecr list-images --region $REGION --repository-name $imageName --filter tagStatus=UNTAGGED --query \'imageIds[*]\' --output text | while read imageId; do aws ecr batch-delete-image --region eu-west-1 --repository-name $imageName --image-ids imageDigest=\$imageId; done"
                }
            }
        }
	   
        stage("Check image vulnerabilities") {
            agent any
            steps {
                title "Check image vulnerabilities"
                sh """#!/bin/bash -e
                    ECR_LOGIN=\$(aws ecr get-login --region $REGION --no-include-email)
                    \$ECR_LOGIN
                    PASSWORD=\$(echo \$ECR_LOGIN | cut -d' ' -f6)
                    REGISTRY=\$(echo \$ECR_LOGIN | cut -d' ' -f7 | sed 's/https:\\/\\///')
                    ECR_REPOSITORY_URI=\$REGISTRY"/${imageName}:d-${env.GIT_COMMIT}"
                    wget -q https://github.com/optiopay/klar/releases/download/v2.4.0/klar-2.4.0-linux-amd64
                    mv ./klar-2.4.0-linux-amd64 ./klar
                    chmod +x ./klar

                    DOCKER_USER=AWS DOCKER_PASSWORD=\$PASSWORD CLAIR_ADDR=$CLAIR_URL CLAIR_OUTPUT=$CLAIR_THRESHOLD ./klar \$ECR_REPOSITORY_URI
                """
            }
        }
        stage("Promotion#1") {
            steps {
                timeout(time: 1, unit: 'HOURS') {
                    input message: 'Deploy in DEV?', ok: 'Go!'
                }
            }
        }
        stage("Deploy to DEV") {
            agent any
            steps {
                script {
                    title "Create a Task Definition revision"
                    env.TASK_REVISION_DEV = sh (
                        script: """
                            aws ecs register-task-definition \
                                --region $REGION \
                                --family "TD-DEV-FEATURE-$serviceName" \
                                --container-definitions "[{\\"name\\": \\"$serviceName\\", \\"image\\": \\"\$(echo $registry | sed 's/https:\\/\\///')/$imageName:d-${env.GIT_COMMIT}\\", \\"essential\\": true, [ \\"portMappings\\": [{\\"containerPort\\": $port, \\"protocol\\": \\"tcp\\"}], \\"dockerLabels\\": {\\"traefik.frontend.rule\\": \\"Host:${serviceName}.dev.node-example.com\\", \\"traefik.enable\\": \\"true\\", \\"traefik.protocol\\": \\"http\\", \\"traefik.port\\": \\"$port\\"}, \\"logConfiguration\\": {\\"logDriver\\": \\"awslogs\\", \\"options\\": {\\"awslogs-create-group\\": \\"true\\", \\"awslogs-group\\": \\"ECS-Dev-ContainerLogs\\", \\"awslogs-region\\": \\"eu-west-1\\", \\"awslogs-stream-prefix\\": \\"$serviceName\\"}}}]" \
                                --placement-constraints "type=\\"memberOf\\",expression=\\"attribute:BusinessDomain == $businessDomain\\"" \
                                --cpu 256 \
                                --memory 100 \
                            | jq -r '.taskDefinition.revision'
                        """,
                        returnStdout: true
                    ).trim()

                    title "Run the Task"
                    env.TASK_ARN_DEV = sh (
                        script: """
                            aws ecs run-task \
                                --region $REGION \
                                --cluster ECS-Dev \
                                --task-definition "TD-DEV-FEATURE-${serviceName}:${env.TASK_REVISION_DEV}" \
                                --count 1 \
                            | jq -r '.tasks[0].taskArn'
                        """,
                        returnStdout: true
                    ).trim()

                    title "Test the Task is up"
                    retry(3) {
                        sleep time: 10, unit: 'SECONDS'
                        ret = sh returnStatus: true, script: """
                            aws ecs describe-tasks --region $REGION --cluster ECS-Dev --tasks ${env.TASK_ARN_DEV} | jq '.tasks[].lastStatus' | grep "RUNNING"
                        """
                        if (ret) {
                            sleep time: 10, unit: 'SECONDS'
                            error 'test failed'
                        }
                    }

                    result "Service available at https://${serviceName}.dev.node-example.com/ "
                }
            }
        }
        stage("Promotion#2") {
            steps {
                timeout(time: 5, unit: 'DAYS') {
                    input message: 'Deploy in QA?', ok: 'Go!'
                }
            }
        }
        stage("Deploy to QA") {
            agent any
            steps {
                script {
                    title "Create a Task Definition revision"
                    env.TASK_REVISION_QA = sh (
                        script: """
                            aws ecs register-task-definition \
                                --region $REGION \
                                --family "TD-DEV-FEATURE-$serviceName" \
                                --container-definitions "[{\\"name\\": \\"$serviceName\\", \\"image\\": \\"\$(echo $registry | sed 's/https:\\/\\///')/$imageName:d-${env.GIT_COMMIT}\\", \\"essential\\": true, [ \\"portMappings\\": [{\\"containerPort\\": $port, \\"protocol\\": \\"tcp\\"}], \\"dockerLabels\\": {\\"traefik.frontend.rule\\": \\"Host:${serviceName}.qa.node-example.com\\", \\"traefik.enable\\": \\"true\\", \\"traefik.protocol\\": \\"http\\", \\"traefik.port\\": \\"$port\\"}, \\"logConfiguration\\": {\\"logDriver\\": \\"awslogs\\", \\"options\\": {\\"awslogs-create-group\\": \\"true\\", \\"awslogs-group\\": \\"ECS-Dev-ContainerLogs\\", \\"awslogs-region\\": \\"eu-west-1\\", \\"awslogs-stream-prefix\\": \\"$serviceName\\"}}}]" \
                                --placement-constraints "type=\\"memberOf\\",expression=\\"attribute:BusinessDomain == $businessDomain\\"" \
                                --cpu 256 \
                                --memory 100 \
                            | jq -r '.taskDefinition.revision'
                        """,
                        returnStdout: true
                    ).trim()

                    title "Run the Task"
                    env.TASK_ARN_QA = sh (
                        script: """
                            aws ecs run-task \
                                --region $REGION \
                                --cluster ECS-QA \
                                --task-definition "TD-QA-FEATURE-${serviceName}:${env.TASK_REVISION_QA}" \
                                --count 1 \
                            | jq -r '.tasks[0].taskArn'
                        """,
                        returnStdout: true
                    ).trim()

                    title "Test the Task is up"
                    retry(3) {
                        ret = sh returnStatus: true, script: """
                            aws ecs describe-tasks --region eu-west-1 --cluster ECS-QA --tasks ${env.TASK_ARN_QA} | jq '.tasks[].lastStatus' | grep "RUNNING"
                        """
                        if (ret) {
                            sleep time: 10, unit: 'SECONDS'
                            error 'test failed'
                        }
                    }

                    result "Service available at https://${serviceName}.qa.node-example.com/ "
                }
            }
        }
        stage("Finish") {
            steps {
                timeout(time: 5, unit: 'DAYS') {
                    input message: 'Remove all resources?', ok: 'Go!'
                }
            }
        }
    }
    post {
        always {
            node("master") {
                script {
                    title "Remove unused docker images"
                    sh "docker images --filter \"dangling=true\" -q --no-trunc | xargs -r docker rmi"
                    sh "docker images --format '{{.Repository}}:{{.Tag}}' | grep '$imageName' | xargs -r docker rmi"

                    title "Stop Tasks"
                    if (env.TASK_ARN_QA) {
                        sh "aws ecs stop-task --region $REGION --cluster ECS-QA --task ${env.TASK_ARN_QA}"
                    }
                    if (env.TASK_ARN_DEV) {
                        sh "aws ecs stop-task --region $REGION --cluster ECS-Dev --task ${env.TASK_ARN_DEV}"
                    }

                    title "Deregister Task revisions"
                    if (env.TASK_REVISION_QA) {
                        sh "aws ecs deregister-task-definition --region $REGION --task-definition \"TD-QA-FEATURE-${serviceName}:${env.TASK_REVISION_QA}\""
                    }
                    if (env.TASK_REVISION_DEV) {
                        sh "aws ecs deregister-task-definition --region $REGION --task-definition \"TD-DEV-FEATURE-${serviceName}:${env.TASK_REVISION_DEV}\""
                    }

                    title "Remove Image from the registry"
                    sh """
                        eval \$(aws ecr get-login --region $REGION --no-include-email)
                        aws ecr batch-delete-image --region $REGION --repository-name ${imageName} --image-ids imageTag=d-${env.GIT_COMMIT}
                    """
                }
            }
        }
    }
}
