def FAILED_STAGE

pipeline {
    agent any

    parameters {
        booleanParam(name: 'DEPLOY_ONLY', defaultValue: false, description: 'Only deploy')
        booleanParam(name: 'INIT_ENVIRONMENT_VARIABLES', defaultValue: false, description: 'Initialize environment variables')
        string(name: 'RELEASE_TO_BUILD', defaultValue: 'master', description: 'Specify the Git release to build')
        string(name: 'STATIC_RECIPIENTS', defaultValue: 'jennifer.dubra@udc.es, software.dbr@gmail.com', description: 'Comma-separated list of static email recipients')
    }

    environment {
        STATIC_RECIPIENTS = "${params.STATIC_RECIPIENTS}"
        GIT_REPOSITORY='https://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@github.com/dubrajennifer/TFM-CICD-Apache-openmeetings.git'
    }

    tools {
        maven '3.9.4'
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
                    def propertiesContent = sh(script: "printenv", returnStdout: true)
                    writeFile file: "${workspace}/../environment_variables_${env.JOB_NAME}.properties", text: propertiesContent
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
                    sh 'mvn install -DskipTests=true -Dsite.skip=true'

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
                                nexusUrl: NEXUS_IP,
                                groupId: groupId,
                                version: version,
                                repository: PRD_NEXUS_REPOSITORY,
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
                            def propertiesMap = loadPropertiesFromFile()
                            deployToEnvironment(propertiesMap.APP_BLUE_ARN, propertiesMap.APP_GREEN_ARN, propertiesMap.APP_BLUE_IP, propertiesMap.APP_GREEN_IP)

                        }
                    }
                }
            }
            post {
                success {
                    script {
                            if (currentBuild.result == 'SUCCESS') {
                                def propertiesContent = setEnvironmentVariables()
                                // Write the environment variables to a file
                                writeFile file: "${workspace}/../environment_variables_${env.JOB_NAME}.properties", text: propertiesContent
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


def deployToEnvironment( String blueARN, String greenARN, String blueIP, String greenIP) {
    def deploymentCmd = getDeploymentCommand(greenIP)
    def validateCmd = getValidationCommand(blueARN,greenARN,greenIP)

    stage("Deploying") {
        script {
            withCredentials([
                sshUserPrivateKey(
                    credentialsId: APP_PRD_SSH_CREDENTIALS_ID,
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
                sleep time: 15, unit: 'SECONDS' // Wait for 5 seconds
            }
            
        }
    }

    stage("Route traffic") {
        script {
            withCredentials([
                sshUserPrivateKey(
                    credentialsId: APP_PRD_SSH_CREDENTIALS_ID,
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


def getDeploymentCommand(greenIP) {
    return """
        ssh-keyscan -H ${greenIP} >> ~/.ssh/known_hosts
        scp -i \${SSH_KEY} -r ${workspace}/openmeetings-server/target/ ec2-user@${greenIP}:/home/ec2-user/openmeetings
        ssh -i \${SSH_KEY} ec2-user@${greenIP} 'sudo tar -xzf /home/ec2-user/openmeetings/target/*SNAPSHOT.tar.gz --strip-components=1 -C /home/ec2-user/openmeetings-app && sudo /home/ec2-user/openmeetings-app/bin/startup.sh'
    """
}

def getValidationCommand(String blueARN, String greenARN, String greenIP)  {

    return """
        if [ "\$( curl -o /dev/null -s -I -w '%{http_code}' http://${greenIP}:5080/)" -eq 200 ]
        then
            echo "** BUILD IS SUCCESSFUL **"
            curl -I http://${greenIP}:5080/
            aws configure set aws_access_key_id \$AWS_ACCESS_KEY
            aws configure set aws_secret_access_key \$AWS_SECRET_KEY
            aws configure set region us-west-2
            aws elbv2 modify-listener --listener-arn \${APP_LISTENER_ARN} --default-actions '[{\"Type\": \"forward\",\"Order\": 1,\"ForwardConfig\": {\"TargetGroups\": [{\"TargetGroupArn\": \"${blueARN}\", \"Weight\": 0 },{\"TargetGroupArn\": \"${greenARN}\", \"Weight\": 1 }],\"TargetGroupStickinessConfig\": {\"Enabled\": true,\"DurationSeconds\": 1}}}]'
        else
            echo "** BUILD IS FAILED ** Health check returned non 200 status code"
            curl -I http://${greenIP}:5080/
            exit 2
        fi
    """
}

def loadPropertiesFromFile() {
    def propertiesFilePath = "${workspace}/../environment_variables_${env.JOB_NAME}.properties"
    def properties = sh(script: "cat ${propertiesFilePath}", returnStdout: true).trim()

    // Create a map to store the key-value pairs from properties
    def propertiesMap = [:]

    properties.split('\n').each { property ->
        def (key, value) = property.split('=')
        propertiesMap[key] = value // Store the key-value pair in the map
    }

    return propertiesMap
}


def setEnvironmentVariables() {
    def propertiesMap = loadPropertiesFromFile()
    def blueARNtmp = propertiesMap.APP_GREEN_ARN
    def blueIPtmp = propertiesMap.APP_GREEN_IP
    propertiesMap.APP_GREEN_ARN = propertiesMap.APP_BLUE_ARN
    propertiesMap.APP_BLUE_ARN = blueARNtmp
    propertiesMap.APP_GREEN_IP = propertiesMap.APP_BLUE_IP
    propertiesMap.APP_BLUE_IP = blueIPtmp

    def propertiesContent = propertiesMap.collect { key, value -> "${key}=${value}" }.join('\n')

    return propertiesContent
}

