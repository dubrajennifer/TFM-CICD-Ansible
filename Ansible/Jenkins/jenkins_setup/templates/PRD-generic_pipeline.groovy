pipeline {
    agent any

    parameters {
        booleanParam(name: 'SKIP_TESTS', defaultValue: true, description: 'Skip tests')
        booleanParam(name: 'DEPLOY_ONLY', defaultValue: true, description: 'Only deploy')
        booleanParam(name: 'IS_ENVIRONMENT_UP', defaultValue: true, description: 'Is the server running?')
        booleanParam(name: 'INIT_ENVIRONMENT_VARIABLES', defaultValue: true, description: 'Initialize environment variables')
        string(name: 'NEXUS_IP', defaultValue: '34.216.203.59:8081', description: 'IP Nexus')
    }

    environment {
        listenerARN = 'arn:aws:elasticloadbalancing:us-west-2:307819018579:listener/app/BlueGreenALB/f85942f731cefec0/03464d0d49b5a9fe'
        blueARN = 'arn:aws:elasticloadbalancing:us-west-2:307819018579:targetgroup/BlueTarget/6217f7175c85f973'
        greenARN = 'arn:aws:elasticloadbalancing:us-west-2:307819018579:targetgroup/GreenTarget/f044e2ace6db39c0'
        blueIP = '52.10.6.56'
        greenIP = '35.163.209.190'
        JENKINS_AWS_ID='aws-credentials-id'
        APP_SSH_CREDENTIALS_ID='prd-appservers-credentials-id'
        NEXUS_CREDENTIALS_ID='nexus-user-credentials-id'
    }

    tools {
        maven '3.9.3'
    }
    stages {
        stage('Set Environment Variables') {
            when {
                expression {
                    params.INIT_ENVIRONMENT_VARIABLES == true
                }
            }
            steps {
                script {
                    def propertiesContent = 
"""blueARN=${blueARN}
greenARN=${greenARN}
blueIP=${blueIP}
greenIP=${greenIP}
JENKINS_AWS_ID='aws-credentials-id'
APP_SSH_CREDENTIALS_ID='prd-appservers-credentials-id'
NEXUS_CREDENTIALS_ID='nexus-user-credentials-id'
"""
                    // Write the properties to a file
                    writeFile file: "${workspace}/variables.properties", text: propertiesContent
                }
            }
        }
        stage('Get Environment Variables') {
            when {
                expression {
                    params.INIT_ENVIRONMENT_VARIABLES == false
                }
            }
            steps {
                script {
                    def properties = sh(script: "cat ${workspace}/variables.properties", returnStdout: true).trim()
                    properties.split('\n').each { property ->
                        def (key, value) = property.split('=')
                        env."${key}" = value
                    }
                }
            }
        }

        stage('Checkout') {
            steps {
                script {
                    def deployOnly = params.DEPLOY_ONLY
                    if (!deployOnly) {
                        cleanWs()
                    }
                }
                // Checkout the Git repository
                git 'https://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@github.com/dubrajennifer/TFM-CICD-Apache-openmeetings.git'
            }
        }

        stage('Package') {
            steps {
                script {
                    def deployOnly = params.DEPLOY_ONLY
                    if (!deployOnly) {
                        sh 'mvn install -DskipTests=true -Dwicket.configuration=DEVELOPMENT -Dsite.skip=true'
                    }

                }
            }
        }
       

        stage("Publish to Nexus Repository Manager") {
            steps {
                script {
                    def parentPomFilePath = "${workspace}/pom.xml"
                    def modules = sh(
                        script: "grep '<module>' ${parentPomFilePath} | sed 's/^.*<module>\\(.*\\)<\\/module>.*\$'/'\\1'/",
                        returnStdout: true
                    ).trim().split('\n')

                    // Remove the parent module from the list (if present)
                    modules = modules.findAll { it != 'openmeetings-parent' }

                    modules.each { module ->
                        def pomFilePath = "${workspace}/${module}/pom.xml"
                        echo "POM file path: ${pomFilePath}"

                        // Use sh and grep to extract the necessary details from pom.xml
                        def groupId = sh(script: "grep -m 1 '<groupId>' ${pomFilePath} | sed 's/^.*<groupId>\\(.*\\)<\\/groupId>.*\$'/'\\1'/", returnStdout: true).trim()
                        def artifactId = sh(script: "grep -m 1 '<artifactId>' ${pomFilePath} | sed 's/^.*<artifactId>\\(.*\\)<\\/artifactId>.*\$'/'\\1'/", returnStdout: true).trim()
                        def version = sh(script: "grep -m 1 '<version>' ${pomFilePath} | sed 's/^.*<version>\\(.*\\)<\\/version>.*\$'/'\\1'/", returnStdout: true).trim()
                        def packaging = sh(script: "grep -m 1 '<packaging>' ${pomFilePath} | sed 's/^.*<packaging>\\(.*\\)<\\/packaging>.*\$'/'\\1'/", returnStdout: true).trim()

                        def jarFileName = "${module}-${version}.${packaging}"
                        def jarFilePath = "${workspace}/${module}/target/${jarFileName}"

                        if (fileExists(jarFilePath)) {
                            echo "*** File: ${jarFileName}, group: ${groupId}, packaging: ${packaging}, version ${version}";
                            nexusArtifactUploader(
                                nexusVersion: "nexus3",
                                protocol: "http",
                                nexusUrl: params.NEXUS_IP,
                                groupId: groupId,
                                version: version,
                                repository: "maven-nexus-repo",
                                credentialsId: ${NEXUS_CREDENTIALS_ID},
                                artifacts: [
                                    [artifactId: "${module}", classifier: '', file: jarFilePath, type: "${packaging}"],
                                    [artifactId: "${module}", classifier: '', file: pomFilePath, type: "pom"]
                                ]
                            )
                        } else {
                            echo "*** Skipping: ${jarFileName} not found in ${jarFilePath}"
                        }
                    }
                }
            }
        }


        stage('Deploy') {
            stage('Green') {
                steps {
                    script {
                        deployToEnvironment()
                    }
                }
            }
            post {
                success {
                    script {
                        if (currentBuild.result == 'SUCCESS') {
                                def blueARNtemp = env.blueARN
                                env.blueARN = env.greenARN
                                env.greenARN = blueARNtemp
                                
                                def blueIPtemp = env.blueIP
                                env.blueIP = env.greenIP
                                env.greenIP = blueIPtemp
                                
                               def propertiesContent = 
"""blueARN=${env.blueARN}
greenARN=${env.greenARN}
blueIP=${env.blueIP}
greenIP=${env.greenIP}
JENKINS_AWS_ID='aws-credentials-id'
APP_SSH_CREDENTIALS_ID='prd-appservers-credentials-id'
NEXUS_CREDENTIALS_ID='nexus-user-credentials-id'
"""
                                // Write the properties to a file
                                writeFile file: "${workspace}/variables.properties", text: propertiesContent
                        }
                    }
                }
            }
        }
}
}


def deployToEnvironment() {
    def deploymentCmd = getDeploymentCommand()
    def validateCmd = getValidationCommand()

    stage("Deploying to Green group") {
        script {
            withCredentials([
                sshUserPrivateKey(
                    credentialsId: ${APP_SSH_CREDENTIALS_ID},
                    keyFileVariable: 'SSH_KEY',
                    passphraseVariable: 'SSH_PASSPHRASE',
                    usernameVariable: 'SSH_USERNAME'
                ),
                [
                    $class: 'AmazonWebServicesCredentialsBinding',
                    accessKeyVariable: 'AWS_ACCESS_KEY',
                    credentialsId: params.JENKINS_AWS_ID,
                    secretKeyVariable: 'AWS_SECRET_KEY'
                ]
            ]) {
                sh deploymentCmd
            }
        }
    }

    stage("Validate and Add Green group for testing") {
        script {
            withCredentials([
                sshUserPrivateKey(
                    credentialsId: ${APP_SSH_CREDENTIALS_ID},
                    keyFileVariable: 'SSH_KEY',
                    passphraseVariable: 'SSH_PASSPHRASE',
                    usernameVariable: 'SSH_USERNAME'
                ),
                [
                    $class: 'AmazonWebServicesCredentialsBinding',
                    accessKeyVariable: 'AWS_ACCESS_KEY',
                    credentialsId: params.JENKINS_AWS_ID,
                    secretKeyVariable: 'AWS_SECRET_KEY'
                ]
            ]) {
                sh validateCmd
            }
        }
    }
}


def getDeploymentCommand() {
    return """
        ssh-keyscan -H ${greenIP} >> ~/.ssh/known_hosts
        scp -i \${SSH_KEY} -r /var/lib/jenkins/workspace/*-openmeetigs/openmeetings-server/target/ ec2-user@${greenIP}:/home/ec2-user/openmeetings
        ssh -i \${SSH_KEY} ec2-user@${greenIP} 'sudo tar -xzf /home/ec2-user/openmeetings/target/*SNAPSHOT.tar.gz --strip-components=1 -C /home/ec2-user/openmeetings-app && sudo /home/ec2-user/openmeetings-app/bin/startup.sh'
    """
}

def getValidationCommand() {

    return """
        if [ "\$( curl -o /dev/null -s -I -w '%{http_code}' http://${greenIP}:5080/)" -eq 200 ]
        then
            echo "** BUILD IS SUCCESSFUL **"
            curl -I http://${greenIP}:5080/
            aws configure set aws_access_key_id \$AWS_ACCESS_KEY
            aws configure set aws_secret_access_key \$AWS_SECRET_KEY
            aws configure set region us-west-2
            aws elbv2 modify-listener --listener-arn \${listenerARN} --default-actions '[{\"Type\": \"forward\",\"Order\": 1,\"ForwardConfig\": {\"TargetGroups\": [{\"TargetGroupArn\": \"${blueARN}\", \"Weight\": 0 },{\"TargetGroupArn\": \"${greenARN}\", \"Weight\": 1 }],\"TargetGroupStickinessConfig\": {\"Enabled\": true,\"DurationSeconds\": 1}}}]'
        else
            echo "** BUILD IS FAILED ** Health check returned non 200 status code"
            curl -I http://${greenIP}:5080/
            exit 2
        fi
    """
}