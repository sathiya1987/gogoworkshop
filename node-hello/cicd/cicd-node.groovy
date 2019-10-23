// inventoryoptimization-ui
// Test and build "inventoryoptimization-ui" docker image and push it to the registry
//
// triggered by a commit on the repository master branch

def title(cmd) {
    sh('#!/bin/sh -e\necho -e "\\e[1m\\e[34m' + cmd + '\\e[39m\\e[0m"')
}

def warn(cmd) {
    sh('#!/bin/sh -e\necho -e "\\e[1m\\e[35m' + cmd + '\\e[39m\\e[0m"')
}

pipeline {

   options {
        ansiColor('xterm')
        withAWS(role: 'arn:aws:iam::578282232042:role/Jenkins-Role-NNA-Prod', roleAccount: '578282232042')
    }

    environment {
        jenkinsCredentialsForBitbucket = "devopssvc"
        repository = "your git url"
        registry = "https://578282232042.dkr.ecr.us-east-1.amazonaws.com"
        imageName = "image name"
        REGION = "us-east-1"
    }

    agent any
    tools {
        nodejs "NodeJS"
        }

    stages {
        stage("Clone Git") {
            steps {
                title "Clone the Bitbucket repository"
                script {
                    def scmVars = checkout([$class: 'GitSCM', branches: [[name: '*/$GitBranch']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CloneOption', noTags: false, reference: '', shallow: true]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: jenkinsCredentialsForBitbucket, url: repository]]])
                    env.GIT_COMMIT = scmVars.GIT_COMMIT.substring(0, 8)
                }
            }
        }
        stage("Code Quality") {
            steps {
                script {
                    try {
                        dir("dockerfile-scanner") {
                            title "Check Dockerfile for CIS compliance"
                            checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[credentialsId: jenkinsCredentialsForBitbucket, url: '$DOCKERFILE_SCANNER_REPO']]])
                           sh "python3 scanner.py ../DockerFile"
                            env.tag = "m"
                        }
                    } catch(Exception error) {
                        warn "WARN: Code quality tests failed!"
                        currentBuild.result = "UNSTABLE"
                        env.tag = "w"
                    }
                }
            }
        }

        stage("Build") {
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
            steps {
                script {
                    title "Push the Docker image to the registry"
                    sh "eval \$(/var/lib/jenkins/bin/aws ecr get-login --region $REGION --no-include-email)"
                    sh "/var/lib/jenkins/bin/aws ecr describe-repositories --region $REGION --repository-names $imageName || /var/lib/jenkins/bin/aws ecr create-repository --region $REGION --repository-name $imageName"
                    docker.withRegistry(registry) {
                        docker.image(imageName).push("${env.tag}-${env.GIT_COMMIT}")
                        docker.image(imageName).push('latest')
                    }
                    //sh "/var/lib/jenkins/bin/aws ecr list-images --region $REGION --repository-name $imageName --filter tagStatus=UNTAGGED --query 'imageIds[*]' --output text | while read imageId; do /var/lib/jenkins/bin/aws ecr batch-delete-image --region $REGION --repository-name $imageName --image-ids imageDigest=\$imageId; done"
                }
            }
        }
    }
    post {
        always {
            title "Remove unused docker images"
            sh "docker images --filter \"dangling=true\" -q --no-trunc | xargs -r docker rmi --force"
            sh "docker images --format '{{.Repository}}:{{.Tag}}' | grep '$imageName' | xargs -r docker rmi --force"
        }
        success {
            title "Start Dev - UI deployment"
            build job: "CD-IO-Angular-Dev", wait: false
        }
    }
}

