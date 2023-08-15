def FAILED_STAGE

pipeline {
    agent any

    parameters {
        booleanParam(name: &amp;apos;SKIP_TESTS&amp;apos;, defaultValue: false, description: &amp;apos;Skip tests&amp;apos;)
        booleanParam(name: &amp;apos;SKIP_SONAR&amp;apos;, defaultValue: false, description: &amp;apos;Skip sonar analysis&amp;apos;)
        booleanParam(name: &amp;apos;DEPLOY_ONLY&amp;apos;, defaultValue: false, description: &amp;apos;Only deploy&amp;apos;)
        string(name: &amp;apos;BRANCH_TO_BUILD&amp;apos;, defaultValue: &amp;apos;master&amp;apos;, description: &amp;apos;Specify the Git branch to build&amp;apos;)
        string(name: &amp;apos;STATIC_RECIPIENTS&amp;apos;, defaultValue: &amp;apos;jennifer.dubra@udc.es, software.dbr@gmail.com&amp;apos;, description: &amp;apos;Comma-separated list of static email recipients&amp;apos;)
    }

    environment {       
        STATIC_RECIPIENTS = &amp;quot;${params.STATIC_RECIPIENTS}&amp;quot;
        GIT_REPOSITORY=&amp;apos;https://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@github.com/dubrajennifer/TFM-CICD-Apache-openmeetings.git&amp;apos;
    }

    tools {
        maven &amp;apos;3.9.4&amp;apos;
    }
    stages {
       stage(&amp;apos;Checkout&amp;apos;) {
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
                    
                    echo &amp;quot;Event payload ${eventPayload}&amp;quot;

                    if (branch == &amp;apos;refs/heads/master&amp;apos;) {
                        echo &amp;quot;Webhook event is a commit to the master branch.&amp;quot;
                        echo &amp;quot;Commit message: ${commitMessage}&amp;quot;

                        // Perform actions specific to commits on master
                    }
                    
                    if (branch != &amp;apos;refs/heads/master&amp;apos;) {
                        currentBuild.result = &amp;apos;ABORTED&amp;apos;
                        error &amp;quot;Build triggered by non-master commit. Build is aborted.&amp;quot;
                    }
                    cleanWs()
                }
                // Checkout the Git repository
               git branch: &amp;quot;${params.BRANCH_TO_BUILD}&amp;quot;, url: &amp;quot;${GIT_REPOSITORY}&amp;quot;
            }
            post {
                always {
                    script {
                        if (currentBuild.result == &amp;apos;FAILURE&amp;apos; || currentBuild.result == &amp;apos;UNSTABLE&amp;apos;)  {
                            FAILED_STAGE=env.STAGE_NAME
                        }
                    }
                }
            }
        }

        stage(&amp;apos;Sonar&amp;apos;) {
            when {
                expression {
                    return !params.SKIP_SONAR
                }
            }
            steps {
                script {
                    withSonarQubeEnv(&amp;apos;SonarQubeServer&amp;apos;) {
                        script {
                            def sonarProperties = [
                                &amp;quot;-Dsonar.projectKey=${env.JOB_NAME}&amp;quot;,
                                &amp;quot;-Dsonar.projectName${env.JOB_NAME}&amp;quot;
                            ]
                            
                            // Run the SonarQube scanner with the defined properties and other Maven goals
                            sh &amp;quot;mvn clean verify sonar:sonar -DskipTests ${sonarProperties.join(&amp;apos; &amp;apos;)}&amp;quot;
                        }
                    }
                }
            }
            post {
                always {
                    script {
                        if (currentBuild.result == &amp;apos;FAILURE&amp;apos; || currentBuild.result == &amp;apos;UNSTABLE&amp;apos;)  {
                            FAILED_STAGE=env.STAGE_NAME
                        }
                    }
                }
            }
        }

        stage(&amp;apos;Test&amp;apos;) {
            when {
                expression {
                    return !params.SKIP_TESTS
                }
            }
            steps {
                sh &amp;apos;mvn test&amp;apos;
            }
            post {
                always {
                    script {
                        if (currentBuild.result == &amp;apos;FAILURE&amp;apos; || currentBuild.result == &amp;apos;UNSTABLE&amp;apos;)  {
                            FAILED_STAGE=env.STAGE_NAME
                        }
                        cleanWs()
                    }
                }
            }
        }


        stage(&amp;apos;Package&amp;apos;) {
            when {
                expression {
                    return !params.DEPLOY_ONLY
                }
            }
            steps {
                script {
                    sh &amp;apos;mvn install -DskipTests=true -Dwicket.configuration=DEVELOPMENT -Dsite.skip=true&amp;apos;
                }
            }
            post {
                always {
                    script {
                        if (currentBuild.result == &amp;apos;FAILURE&amp;apos; || currentBuild.result == &amp;apos;UNSTABLE&amp;apos;)  {
                            FAILED_STAGE=env.STAGE_NAME
                        }
                    }
                }
            }
        }
       

        stage(&amp;quot;Nexus&amp;quot;) {
            when {
                expression {
                    return !params.DEPLOY_ONLY
                }
            }
            steps {
                script {
                    def parentPomFilePath = &amp;quot;${workspace}/pom.xml&amp;quot;
                    def modules = sh(
                        script: &amp;quot;grep &amp;apos;&amp;lt;module&amp;gt;&amp;apos; ${parentPomFilePath} | sed &amp;apos;s/^.*&amp;lt;module&amp;gt;\\(.*\\)&amp;lt;\\/module&amp;gt;.*\$&amp;apos;/&amp;apos;\\1&amp;apos;/&amp;quot;,
                        returnStdout: true
                    ).trim().split(&amp;apos;\n&amp;apos;)

                    modules.each { module -&amp;gt;
                        def pomFilePath = &amp;quot;${workspace}/${module}/pom.xml&amp;quot;
                        echo &amp;quot;POM file path: ${pomFilePath}&amp;quot;

                        // Use sh and grep to extract the necessary details from pom.xml
                        def groupId = sh(script: &amp;quot;grep -m 1 &amp;apos;&amp;lt;groupId&amp;gt;&amp;apos; ${pomFilePath} | sed &amp;apos;s/^.*&amp;lt;groupId&amp;gt;\\(.*\\)&amp;lt;\\/groupId&amp;gt;.*\$&amp;apos;/&amp;apos;\\1&amp;apos;/&amp;quot;, returnStdout: true).trim()
                        def artifactId = sh(script: &amp;quot;grep -m 1 &amp;apos;&amp;lt;artifactId&amp;gt;&amp;apos; ${pomFilePath} | sed &amp;apos;s/^.*&amp;lt;artifactId&amp;gt;\\(.*\\)&amp;lt;\\/artifactId&amp;gt;.*\$&amp;apos;/&amp;apos;\\1&amp;apos;/&amp;quot;, returnStdout: true).trim()
                        def version = sh(script: &amp;quot;grep -m 1 &amp;apos;&amp;lt;version&amp;gt;&amp;apos; ${pomFilePath} | sed &amp;apos;s/^.*&amp;lt;version&amp;gt;\\(.*\\)&amp;lt;\\/version&amp;gt;.*\$&amp;apos;/&amp;apos;\\1&amp;apos;/&amp;quot;, returnStdout: true).trim()
                        def packaging = sh(script: &amp;quot;grep -m 1 &amp;apos;&amp;lt;packaging&amp;gt;&amp;apos; ${pomFilePath} | sed &amp;apos;s/^.*&amp;lt;packaging&amp;gt;\\(.*\\)&amp;lt;\\/packaging&amp;gt;.*\$&amp;apos;/&amp;apos;\\1&amp;apos;/&amp;quot;, returnStdout: true).trim()

                        def jarFileName = &amp;quot;${module}-${version}.${packaging}&amp;quot;
                        def jarFilePath = &amp;quot;${workspace}/${module}/target/${jarFileName}&amp;quot;

                        if (fileExists(jarFilePath)) {
                            echo &amp;quot;*** File: ${jarFileName}, group: ${groupId}, packaging: ${packaging}, version ${version}&amp;quot;;
                            nexusArtifactUploader(
                                nexusVersion: &amp;quot;nexus3&amp;quot;,
                                protocol: &amp;quot;http&amp;quot;,
                                nexusUrl: NEXUS_IP,
                                groupId: groupId,
                                version: version,
                                repository: STG_NEXUS_REPOSITORY,
                                credentialsId: NEXUS_CREDENTIALS_ID,
                                artifacts: [
                                    [artifactId: &amp;quot;${module}&amp;quot;, classifier: &amp;apos;&amp;apos;, file: jarFilePath, type: &amp;quot;${packaging}&amp;quot;],
                                    [artifactId: &amp;quot;${module}&amp;quot;, classifier: &amp;apos;&amp;apos;, file: pomFilePath, type: &amp;quot;pom&amp;quot;]
                                ]
                            )
                        } else {
                            echo &amp;quot;*** Skipping: ${jarFileName} not found in ${jarFilePath}&amp;quot;
                        }
                    }
                }
            }
            post {
                always {
                    script {
                        if (currentBuild.result == &amp;apos;FAILURE&amp;apos; || currentBuild.result == &amp;apos;UNSTABLE&amp;apos;)  {
                            FAILED_STAGE=env.STAGE_NAME
                        }
                    }
                }
            }
        }

        stage(&amp;quot;Deploying to STG&amp;quot;) {
            steps{
                script {
                    withCredentials([
                        sshUserPrivateKey(
                            credentialsId: APP_STG_SSH_CREDENTIALS_ID,
                            keyFileVariable: &amp;apos;SSH_KEY&amp;apos;,
                            passphraseVariable: &amp;apos;SSH_PASSPHRASE&amp;apos;,
                            usernameVariable: &amp;apos;SSH_USERNAME&amp;apos;
                        )
                    ]) {
                        sh  &amp;quot;&amp;quot;&amp;quot;
                            ssh-keyscan -H ${APP_STG_SERVER_IP} &amp;gt;&amp;gt; ~/.ssh/known_hosts
                            scp -i \${SSH_KEY} -r ${workspace}/openmeetings-server/target/ ec2-user@${APP_STG_SERVER_IP}:/home/ec2-user/openmeetings
                            ssh -i \${SSH_KEY} ec2-user@${APP_STG_SERVER_IP} &amp;apos;sudo tar -xzf /home/ec2-user/openmeetings/target/*SNAPSHOT.tar.gz --strip-components=1 -C /home/ec2-user/openmeetings-app &amp;&amp; sudo /home/ec2-user/openmeetings-app/bin/startup.sh&amp;apos;
                        &amp;quot;&amp;quot;&amp;quot;
                    }
                }
            }
            post {
                always {
                    script {
                        if (currentBuild.result == &amp;apos;FAILURE&amp;apos; || currentBuild.result == &amp;apos;UNSTABLE&amp;apos;)  {
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
                if (currentBuild.result == &amp;apos;FAILURE&amp;apos; || currentBuild.result == &amp;apos;UNSTABLE&amp;apos;) {
                    def staticRecipients = env.STATIC_RECIPIENTS
                    emailext subject: &amp;quot;[JENKINS][${env.JOB_NAME}] ${currentBuild.result} at ${FAILED_STAGE}&amp;quot;,
                             body: &amp;quot;The build has failed or is unstable in stage: ${FAILED_STAGE} \nCheck console output at ${env.BUILD_URL} to view the results.&amp;quot;,
                             to: &amp;quot;${staticRecipients}&amp;quot;
                }
            }
        }
    }

}
