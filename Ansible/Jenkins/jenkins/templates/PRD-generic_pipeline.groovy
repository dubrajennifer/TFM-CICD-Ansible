pipeline {
    agent any

    parameters {
        booleanParam(name: 'DEPLOY_ONLY', defaultValue: false, description: 'Only deploy')
        booleanParam(name: 'INIT_ENVIRONMENT_VARIABLES', defaultValue: false, description: 'Initialize environment variables')
        string(name: 'NEXUS_IP', defaultValue: '54.212.45.242:8081', description: 'IP Nexus')
        string(name: 'RELEASE_TO_BUILD', defaultValue: 'master', description: 'Specify the Git release to build')
        string(name: 'STATIC_RECIPIENTS', defaultValue: 'jennifer.dubra@udc.es, software.dbr@gmail.com', description: 'Comma-separated list of static email recipients')
    }

    environment {
        listenerARN = 'arn:aws:elasticloadbalancing:us-west-2:307819018579:listener/app/BlueGreenALB/3135f40abe73eff2/3ab50eaad97a6028'
        blueARN = 'arn:aws:elasticloadbalancing:us-west-2:307819018579:targetgroup/ATarget/224a2ccdbd8e0a4e'
        greenARN = 'arn:aws:elasticloadbalancing:us-west-2:307819018579:targetgroup/BTarget/178ddca1e7962c2e'
        blueIP = '54.186.211.106'
        greenIP = '18.236.147.0'
        JENKINS_AWS_ID='aws-credentials-id'
        APP_SSH_CREDENTIALS_ID='prd-appservers-credentials-id'
        NEXUS_CREDENTIALS_ID='nexus-user-credentials-id'
        STATIC_RECIPIENTS = "${params.STATIC_RECIPIENTS}"
        GIT_REPOSITORY='https://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@github.com/dubrajennifer/TFM-CICD-Apache-openmeetings.git'

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
                    def propertiesContent = setEnvironmentVariables(env.blueARN,env.greenARN,env.blueIP, env.greenIP)
                    // Write the environment variables to a file
                    writeFile file: "${workspace}/variables.properties", text: propertiesContent
                }
            }
            post {
                always {
                    script {
                        if (currentBuild.result == 'FAILURE' || currentBuild.result == 'UNSTABLE')  {
                            FAILED_STAGE=env.STAGE_NAME
                        }
                    }
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
            post {
                always {
                    script {
                        if (currentBuild.result == 'FAILURE' || currentBuild.result == 'UNSTABLE')  {
                            FAILED_STAGE=env.STAGE_NAME
                        }
                    }
                }
            }
        }

       stage('Checkout') {
            when {
                expression {
                    return !params.DEPLOY_ONLY
                }
            }
            steps {
                script { 
                    cleanWs()
                }
                // Checkout the Git repository
               git branch: "${params.RELEASE_TO_BUILD}", url: "${GIT_REPOSITORY}"
            }
            post {
                always {
                    script {
                        if (currentBuild.result == 'FAILURE' || currentBuild.result == 'UNSTABLE')  {
                            FAILED_STAGE=env.STAGE_NAME
                        }
                    }
                }
            }
        }

        stage('Package') {
            when {
                expression {
                    return !params.DEPLOY_ONLY
                }
            }
            steps {
                script {
                    sh 'mvn install -DskipTests=true -Dwicket.configuration=DEVELOPMENT -Dsite.skip=true'

                }
            }
            post {
                always {
                    script {
                        if (currentBuild.result == 'FAILURE' || currentBuild.result == 'UNSTABLE')  {
                            FAILED_STAGE=env.STAGE_NAME
                        }
                    }
                }
            }
        }
       

        stage("Nexus") {
            when {
                expression {
                    return !params.DEPLOY_ONLY
                }
            }
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
                                repository: "prd-maven-repository",
                                credentialsId: NEXUS_CREDENTIALS_ID,
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
            post {
                always {
                    script {
                        if (currentBuild.result == 'FAILURE' || currentBuild.result == 'UNSTABLE')  {
                            FAILED_STAGE=env.STAGE_NAME
                        }
                    }
                }
            }
        }


        stage('Deploy') {
            parallel {
                stage('Green') {
                    steps {
                        script {
                            deployToEnvironment()
                        }
                    }
                }
            }
            post {
                success {
                    script {
                        if (currentBuild.result == 'SUCCESS') {
                            def propertiesContent = setEnvironmentVariables(env.greenARN, env.blueARN, env.greenIP,env.blueIP)
                            // Write the environment variables to a file
                            writeFile file: "${env.WORKSPACE}/variables.properties", text: propertiesContent
                        }
                        
                        if (currentBuild.result == 'FAILURE' || currentBuild.result == 'UNSTABLE')  {
                            FAILED_STAGE=env.STAGE_NAME
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                if (currentBuild.result == 'FAILURE' || currentBuild.result == 'UNSTABLE') {
                    emailext subject: "[JENKINS][${env.JOB_NAME}] ${currentBuild.result} at ${FAILED_STAGE}",
                             body: "The build has failed or is unstable in stage: ${FAILED_STAGE} \nCheck console output at ${env.BUILD_URL} to view the results.",
                             to: "${params.STATIC_RECIPIENTS}"
                }
            }
        }
    }
}


def deployToEnvironment() {
    def deploymentCmd = getDeploymentCommand()
    def validateCmd = getValidationCommand()

    stage("Deploying") {
        script {
            withCredentials([
                sshUserPrivateKey(
                    credentialsId: APP_SSH_CREDENTIALS_ID,
                    keyFileVariable: 'SSH_KEY',
                    passphraseVariable: 'SSH_PASSPHRASE',
                    usernameVariable: 'SSH_USERNAME'
                ),
                [
                    $class: 'AmazonWebServicesCredentialsBinding',
                    accessKeyVariable: 'AWS_ACCESS_KEY',
                    credentialsId: JENKINS_AWS_ID,
                    secretKeyVariable: 'AWS_SECRET_KEY'
                ]
            ]) {
                sh deploymentCmd
            }
            
        }
    }

    stage("Route traffic") {
        script {
            withCredentials([
                sshUserPrivateKey(
                    credentialsId: APP_SSH_CREDENTIALS_ID,
                    keyFileVariable: 'SSH_KEY',
                    passphraseVariable: 'SSH_PASSPHRASE',
                    usernameVariable: 'SSH_USERNAME'
                ),
                [
                    $class: 'AmazonWebServicesCredentialsBinding',
                    accessKeyVariable: 'AWS_ACCESS_KEY',
                    credentialsId: JENKINS_AWS_ID,
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
        scp -i \${SSH_KEY} -r ${workspace}/openmeetings-server/target/ ec2-user@${greenIP}:/home/ec2-user/openmeetings
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

def setEnvironmentVariables( String arnBlue, String arnGreen, String ipBlue, String ipGreen) {
    return """
blueARN=${arnBlue}
greenARN=${arnGreen}
blueIP=${ipBlue}
greenIP=${ipGreen}
JENKINS_AWS_ID='aws-credentials-id'
APP_SSH_CREDENTIALS_ID='prd-appservers-credentials-id'
NEXUS_CREDENTIALS_ID='nexus-user-credentials-id'
GIT_REPOSITORY='https://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@github.com/dubrajennifer/TFM-CICD-Apache-openmeetings.git'

"""
}