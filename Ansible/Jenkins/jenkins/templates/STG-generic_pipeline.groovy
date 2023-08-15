def FAILED_STAGE

pipeline {
    agent any

    parameters {
        booleanParam(name: &apos;SKIP_TESTS&apos;, defaultValue: false, description: &apos;Skip tests&apos;)
        booleanParam(name: &apos;SKIP_SONAR&apos;, defaultValue: false, description: &apos;Skip sonar analysis&apos;)
        booleanParam(name: &apos;DEPLOY_ONLY&apos;, defaultValue: false, description: &apos;Only deploy&apos;)
        string(name: &apos;BRANCH_TO_BUILD&apos;, defaultValue: &apos;master&apos;, description: &apos;Specify the Git branch to build&apos;)
        string(name: &apos;STATIC_RECIPIENTS&apos;, defaultValue: &apos;jennifer.dubra@udc.es, software.dbr@gmail.com&apos;, description: &apos;Comma-separated list of static email recipients&apos;)
    }

    environment {
        STATIC_RECIPIENTS = &quot;${params.STATIC_RECIPIENTS}&quot;
        GIT_REPOSITORY=&apos;https://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@github.com/dubrajennifer/TFM-CICD-Apache-openmeetings.git&apos;
    }

    tools {
        maven &apos;3.9.4&apos;
    }
    stages {
       stage(&apos;Checkout&apos;) {
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

                    echo &quot;Event payload ${eventPayload}&quot;

                    if (branch == &apos;refs/heads/master&apos;) {
                        echo &quot;Webhook event is a commit to the master branch.&quot;
                        echo &quot;Commit message: ${commitMessage}&quot;

                        // Perform actions specific to commits on master
                    }

                    if (branch != &apos;refs/heads/master&apos;) {
                        currentBuild.result = &apos;ABORTED&apos;
                        error &quot;Build triggered by non-master commit. Build is aborted.&quot;
                    }
                    cleanWs()
                }
                // Checkout the Git repository
               git branch: &quot;${params.BRANCH_TO_BUILD}&quot;, url: &quot;${GIT_REPOSITORY}&quot;
            }
            post {
                always {
                    script {
                        if (currentBuild.result == &apos;FAILURE&apos; || currentBuild.result == &apos;UNSTABLE&apos;)  {
                            FAILED_STAGE=env.STAGE_NAME
                        }
                    }
                }
            }
        }

        stage(&apos;Sonar&apos;) {
            when {
                expression {
                    return !params.SKIP_SONAR
                }
            }
            steps {
                script {
                    withSonarQubeEnv(&apos;SonarQubeServer&apos;) {
                        script {
                            def sonarProperties = [
                                &quot;-Dsonar.projectKey=${env.JOB_NAME}&quot;,
                                &quot;-Dsonar.projectName${env.JOB_NAME}&quot;
                            ]

                            // Run the SonarQube scanner with the defined properties and other Maven goals
                            sh &quot;mvn clean verify sonar:sonar -DskipTests ${sonarProperties.join(&apos; &apos;)}&quot;
                        }
                    }
                }
            }
            post {
                always {
                    script {
                        if (currentBuild.result == &apos;FAILURE&apos; || currentBuild.result == &apos;UNSTABLE&apos;)  {
                            FAILED_STAGE=env.STAGE_NAME
                        }
                    }
                }
            }
        }

        stage(&apos;Test&apos;) {
            when {
                expression {
                    return !params.SKIP_TESTS
                }
            }
            steps {
                sh &apos;mvn test&apos;
            }
            post {
                always {
                    script {
                        if (currentBuild.result == &apos;FAILURE&apos; || currentBuild.result == &apos;UNSTABLE&apos;)  {
                            FAILED_STAGE=env.STAGE_NAME
                        }
                        cleanWs()
                    }
                }
            }
        }


        stage(&apos;Package&apos;) {
            when {
                expression {
                    return !params.DEPLOY_ONLY
                }
            }
            steps {
                script {
                    sh &apos;mvn install -DskipTests=true -Dwicket.configuration=DEVELOPMENT -Dsite.skip=true&apos;
                }
            }
            post {
                always {
                    script {
                        if (currentBuild.result == &apos;FAILURE&apos; || currentBuild.result == &apos;UNSTABLE&apos;)  {
                            FAILED_STAGE=env.STAGE_NAME
                        }
                    }
                }
            }
        }


        stage(&quot;Nexus&quot;) {
            when {
                expression {
                    return !params.DEPLOY_ONLY
                }
            }
            steps {
                script {
                    def parentPomFilePath = &quot;${workspace}/pom.xml&quot;
                    def modules = sh(
                        script: &quot;grep &apos;&lt;module&gt;&apos; ${parentPomFilePath} | sed &apos;s/^.*&lt;module&gt;\\(.*\\)&lt;\\/module&gt;.*\$&apos;/&apos;\\1&apos;/&quot;,
                        returnStdout: true
                    ).trim().split(&apos;\n&apos;)

                    modules.each { module -&gt;
                        def pomFilePath = &quot;${workspace}/${module}/pom.xml&quot;
                        echo &quot;POM file path: ${pomFilePath}&quot;

                        // Use sh and grep to extract the necessary details from pom.xml
                        def groupId = sh(script: &quot;grep -m 1 &apos;&lt;groupId&gt;&apos; ${pomFilePath} | sed &apos;s/^.*&lt;groupId&gt;\\(.*\\)&lt;\\/groupId&gt;.*\$&apos;/&apos;\\1&apos;/&quot;, returnStdout: true).trim()
                        def artifactId = sh(script: &quot;grep -m 1 &apos;&lt;artifactId&gt;&apos; ${pomFilePath} | sed &apos;s/^.*&lt;artifactId&gt;\\(.*\\)&lt;\\/artifactId&gt;.*\$&apos;/&apos;\\1&apos;/&quot;, returnStdout: true).trim()
                        def version = sh(script: &quot;grep -m 1 &apos;&lt;version&gt;&apos; ${pomFilePath} | sed &apos;s/^.*&lt;version&gt;\\(.*\\)&lt;\\/version&gt;.*\$&apos;/&apos;\\1&apos;/&quot;, returnStdout: true).trim()
                        def packaging = sh(script: &quot;grep -m 1 &apos;&lt;packaging&gt;&apos; ${pomFilePath} | sed &apos;s/^.*&lt;packaging&gt;\\(.*\\)&lt;\\/packaging&gt;.*\$&apos;/&apos;\\1&apos;/&quot;, returnStdout: true).trim()

                        def jarFileName = &quot;${module}-${version}.${packaging}&quot;
                        def jarFilePath = &quot;${workspace}/${module}/target/${jarFileName}&quot;

                        if (fileExists(jarFilePath)) {
                            echo &quot;*** File: ${jarFileName}, group: ${groupId}, packaging: ${packaging}, version ${version}&quot;;
                            nexusArtifactUploader(
                                nexusVersion: &quot;nexus3&quot;,
                                protocol: &quot;http&quot;,
                                nexusUrl: NEXUS_IP,
                                groupId: groupId,
                                version: version,
                                repository: STG_NEXUS_REPOSITORY,
                                credentialsId: NEXUS_CREDENTIALS_ID,
                                artifacts: [
                                    [artifactId: &quot;${module}&quot;, classifier: &apos;&apos;, file: jarFilePath, type: &quot;${packaging}&quot;],
                                    [artifactId: &quot;${module}&quot;, classifier: &apos;&apos;, file: pomFilePath, type: &quot;pom&quot;]
                                ]
                            )
                        } else {
                            echo &quot;*** Skipping: ${jarFileName} not found in ${jarFilePath}&quot;
                        }
                    }
                }
            }
            post {
                always {
                    script {
                        if (currentBuild.result == &apos;FAILURE&apos; || currentBuild.result == &apos;UNSTABLE&apos;)  {
                            FAILED_STAGE=env.STAGE_NAME
                        }
                    }
                }
            }
        }

        stage(&quot;Deploying to STG&quot;) {
            steps{
                script {
                    withCredentials([
                        sshUserPrivateKey(
                            credentialsId: APP_STG_SSH_CREDENTIALS_ID,
                            keyFileVariable: &apos;SSH_KEY&apos;,
                            passphraseVariable: &apos;SSH_PASSPHRASE&apos;,
                            usernameVariable: &apos;SSH_USERNAME&apos;
                        )
                    ]) {
                        sh  &quot;&quot;&quot;
                            ssh-keyscan -H ${APP_STG_SERVER_IP} &gt;&gt; ~/.ssh/known_hosts
                            scp -i \${SSH_KEY} -r ${workspace}/openmeetings-server/target/ ec2-user@${APP_STG_SERVER_IP}:/home/ec2-user/openmeetings
                            ssh -i \${SSH_KEY} ec2-user@${APP_STG_SERVER_IP} &apos;sudo tar -xzf /home/ec2-user/openmeetings/target/*SNAPSHOT.tar.gz --strip-components=1 -C /home/ec2-user/openmeetings-app &amp;&amp; sudo /home/ec2-user/openmeetings-app/bin/startup.sh&apos;
                        &quot;&quot;&quot;
                    }
                }
            }
            post {
                always {
                    script {
                        if (currentBuild.result == &apos;FAILURE&apos; || currentBuild.result == &apos;UNSTABLE&apos;)  {
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
                if (currentBuild.result == &apos;FAILURE&apos; || currentBuild.result == &apos;UNSTABLE&apos;) {
                    def staticRecipients = env.STATIC_RECIPIENTS
                    emailext subject: &quot;[JENKINS][${env.JOB_NAME}] ${currentBuild.result} at ${FAILED_STAGE}&quot;,
                             body: &quot;The build has failed or is unstable in stage: ${FAILED_STAGE} \nCheck console output at ${env.BUILD_URL} to view the results.&quot;,
                             to: &quot;${staticRecipients}&quot;
                }
            }
        }
    }

}