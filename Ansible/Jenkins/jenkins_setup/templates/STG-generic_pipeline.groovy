pipeline {
    agent any

    parameters {
        booleanParam(name: 'SKIP_TESTS', defaultValue: true, description: 'Skip tests')
        booleanParam(name: 'DEPLOY_ONLY', defaultValue: true, description: 'Only deploy')
    }

    environment {
        targetIP = '35.163.209.190'
        SONAR_CREDENTIALS_ID = 'sonar-user-credentials-id'
        JENKINS_AWS_ID='aws-credentials-id'
        APP_SSH_CREDENTIALS_ID='stg-appservers-credentials-id'
        NEXUS_CREDENTIALS_ID='nexus-user-credentials-id'
        SONAR_HOST_URL='http://34.209.212.180:9000'
        SONAR_TOKEN='sqp_e1dbbd824eed6aa08be17f849ac933bbd9f7b54b'
        NEXUS_IP='34.216.203.59:8081'
    }

    tools {
        maven '3.9.3'
    }
    stages {
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

        stage('Sonar') {
            steps {
                script {
                    def deployOnly = params.DEPLOY_ONLY
                    if (!deployOnly) {
                        withSonarQubeEnv('SonarQubeServer') {
                            script {
                                // Set the SonarQube properties, including sonar.login
                                def sonarProperties = [
                                    "-Dsonar.projectKey=Openmeetings",
                                    "-Dsonar.projectName=Openmeetings",
                                    "-Dsonar.host.url=${SONAR_HOST_URL}",
                                    "-Dsonar.login=${SONAR_TOKEN}"
                                ]
                                
                                // Run the SonarQube scanner with the defined properties and other Maven goals
                                sh "mvn clean verify sonar:sonar -DskipTests ${sonarProperties.join(' ')}"
                            }
                        }
                    }
                }
            }
        }
        

        stage('Test') {
            steps {
                script {
                    def deployOnly = params.DEPLOY_ONLY
                    def skipTests = params.SKIP_TESTS
                    if (!deployOnly || skipTests) {
                        try {
                            sh 'mvn test'
                        } catch (Exception e) {
                            catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                                echo "Tests failed but continuing pipeline execution"
                            }
                        }
                    }
                }
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
                                nexusUrl: ${NEXUS_IP},
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

    stage("Deploying to STG") {
        script {
            withCredentials([
                sshUserPrivateKey(
                    credentialsId: ${APP_SSH_CREDENTIALS_ID},
                    keyFileVariable: 'SSH_KEY',
                    passphraseVariable: 'SSH_PASSPHRASE',
                    usernameVariable: 'SSH_USERNAME'
                )
            ]) {
                sh  """
                    ssh-keyscan -H ${targetIP} >> ~/.ssh/known_hosts
                    scp -i \${SSH_KEY} -r /var/lib/jenkins/workspace/*-openmeetigs/openmeetings-server/target/ ec2-user@${targetIP}:/home/ec2-user/openmeetings
                    ssh -i \${SSH_KEY} ec2-user@${targetIP} 'sudo tar -xzf /home/ec2-user/openmeetings/target/*SNAPSHOT.tar.gz --strip-components=1 -C /home/ec2-user/openmeetings-app && sudo /home/ec2-user/openmeetings-app/bin/startup.sh'
                """
            }
        }
    }
}

