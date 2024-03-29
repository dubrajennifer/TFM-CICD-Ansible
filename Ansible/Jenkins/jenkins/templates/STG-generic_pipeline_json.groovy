
def FAILED_STAGE

pipeline {
    agent any

    parameters {
        booleanParam(name: 'SKIP_TESTS', defaultValue: false, description: 'Skip tests')
        booleanParam(name: 'SKIP_SONAR', defaultValue: false, description: 'Skip sonar analysis')
        booleanParam(name: 'DEPLOY_ONLY', defaultValue: false, description: 'Only deploy')
        string(name: 'BRANCH_TO_BUILD', defaultValue: 'master', description: 'Specify the Git branch to build')
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
        stage('Checkout') {
            when {
                expression {
                    return !params.DEPLOY_ONLY
                }
            }
            steps {
                script {
                    def eventPayload = JSON.parse(env.CHANGE_PAYLOAD)
                    def branch = eventPayload.ref
                    def commitMessage = eventPayload.head_commit.message

                    echo "Event payload ${eventPayload}"

                    if (branch == 'refs/heads/master') {
                        echo "Webhook event is a commit to the master branch."
                        echo "Commit message: ${commitMessage}"

                        // Perform actions specific to commits on master
                    }

                    if (branch != 'refs/heads/master') {
                        currentBuild.result = 'ABORTED'
                        error "Build triggered by non-master commit. Build is aborted."
                    }
                    cleanWs()
                }
                // Checkout the Git repository
                git branch: "${params.BRANCH_TO_BUILD}", url: "${GIT_REPOSITORY}"
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
                            def sonarProperties = [
                                "-Dsonar.projectKey=${env.JOB_NAME}",
                                "-Dsonar.projectName${env.JOB_NAME}"
                            ]

                            // Run the SonarQube scanner with the defined properties and other Maven goals
                            sh "mvn clean verify sonar:sonar -DskipTests ${sonarProperties.join(' ')}"
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
                        if (currentBuild.result == 'FAILURE' || currentBuild.result == 'UNSTABLE')  {
                            FAILED_STAGE=env.STAGE_NAME
                        }
                        cleanWs()
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
                                repository: STG_NEXUS_REPOSITORY,
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

        stage("Deploying to STG") {
            steps{
                script {
                    withCredentials([
                        sshUserPrivateKey(
                            credentialsId: APP_STG_SSH_CREDENTIALS_ID,
                            keyFileVariable: 'SSH_KEY',
                            passphraseVariable: 'SSH_PASSPHRASE',
                            usernameVariable: 'SSH_USERNAME'
                        )
                    ]) {
                        sh  """
                            ssh-keyscan -H ${APP_STG_SERVER_IP} >> ~/.ssh/known_hosts
                            scp -i \${SSH_KEY} -r ${workspace}/openmeetings-server/target/ ec2-user@${APP_STG_SERVER_IP}:/home/ec2-user/openmeetings
                            ssh -i \${SSH_KEY} ec2-user@${APP_STG_SERVER_IP} 'sudo tar -xzf /home/ec2-user/openmeetings/target/*SNAPSHOT.tar.gz --strip-components=1 -C /home/ec2-user/openmeetings-app && sudo /home/ec2-user/openmeetings-app/bin/startup.sh'
                        """
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
    }
    post {
        always {
            script {
                if (currentBuild.result == 'FAILURE' || currentBuild.result == 'UNSTABLE') {
                    def staticRecipients = env.STATIC_RECIPIENTS
                    emailext subject: "[JENKINS][${env.JOB_NAME}] ${currentBuild.result} at ${FAILED_STAGE}",
                            body: "The build has failed or is unstable in stage: ${FAILED_STAGE} \nCheck console output at ${env.BUILD_URL} to view the results.",
                            to: "${staticRecipients}"
                }
            }
        }
    }

}
