pipeline {
    agent any

    parameters {
        booleanParam(name: 'SKIP_TESTS', defaultValue: false, description: 'Skip tests')
        booleanParam(name: 'SKIP_SONAR', defaultValue: false, description: 'Skip sonar analysis')
        booleanParam(name: 'DEPLOY_ONLY', defaultValue: false, description: 'Only deploy')
        string(name: 'BRANCH_TO_BUILD', defaultValue: 'master', description: 'Specify the Git branch to build')
    }

    environment {
        APP_SERVER_IP = '35.91.76.128'
        NEXUS_IP='54.212.45.242:8081'
        SONAR_TOKEN='sqa_7fda6cf749854703c42b1423b01829f2a41e21fa'
        SONAR_CREDENTIALS_ID = 'sonar-user-credentials-id'
        JENKINS_AWS_ID='aws-credentials-id'
        APP_SSH_CREDENTIALS_ID='stg-appservers-credentials-id'
        NEXUS_CREDENTIALS_ID='nexus-user-credentials-id'
        SONAR_PROJECT='Openmeetings'
        GIT_REPOSITORY='https://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@github.com/dubrajennifer/TFM-CICD-Apache-openmeetings.git'
    }

    tools {
        maven '3.9.3'
    }
    stages {
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
               git branch: "${params.BRANCH_TO_BUILD}", url: "${GIT_REPOSITORY}"
            }
        }

        stage('Sonar') {
            when {
                expression {
                    return !params.SKIP_SONAR
                }
            }
            steps {
                script {
                    withSonarQubeEnv('SonarQubeServer') {
                        script {
                            // Set the SonarQube properties, including sonar.login
                            def sonarProperties = [
                                "-Dsonar.projectKey=${SONAR_PROJECT}",
                                "-Dsonar.projectName${SONAR_PROJECT}",
                                "-Dsonar.login=${SONAR_TOKEN}"
                            ]
                            
                            // Run the SonarQube scanner with the defined properties and other Maven goals
                            sh "mvn clean verify sonar:sonar -DskipTests ${sonarProperties.join(' ')}"
                        }
                    }
                }
            }
        }
        

        stage('Test') {
            when {
                expression {
                    return !params.SKIP_TESTS
                }
            }
            steps {
                sh 'mvn test'
            }
            post {
                always {
                    script {
                        if (currentBuild.result == 'FAILURE') {
                            currentBuild.result = 'UNSTABLE'
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
                    cleanWs()
                    sh 'mvn install -DskipTests=true -Dwicket.configuration=DEVELOPMENT -Dsite.skip=true'
                }
            }
        }
       

        stage("Publish to Nexus Repository Manager") {
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
                                nexusUrl: ${NEXUS_IP},
                                groupId: groupId,
                                version: version,
                                repository: "stg-maven-repository",
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
        }

    stage("Deploying to STG") {
        steps{
            script {
                withCredentials([
                    sshUserPrivateKey(
                        credentialsId: APP_SSH_CREDENTIALS_ID,
                        keyFileVariable: 'SSH_KEY',
                        passphraseVariable: 'SSH_PASSPHRASE',
                        usernameVariable: 'SSH_USERNAME'
                    )
                ]) {
                    sh  """
                        ssh-keyscan -H ${APP_SERVER_IP} >> ~/.ssh/known_hosts
                        scp -i \${SSH_KEY} -r ${workspace}/openmeetings-server/target/ ec2-user@${APP_SERVER_IP}:/home/ec2-user/openmeetings
                        ssh -i \${SSH_KEY} ec2-user@${APP_SERVER_IP} 'sudo tar -xzf /home/ec2-user/openmeetings/target/*SNAPSHOT.tar.gz --strip-components=1 -C /home/ec2-user/openmeetings-app && sudo /home/ec2-user/openmeetings-app/bin/startup.sh'
                    """
                }
            }
        }
    }
}

}