def FAILED_STAGE

pipeline {
    agent any

    parameters {
        booleanParam(name: &apos;DEPLOY_ONLY&apos;, defaultValue: false, description: &apos;Only deploy&apos;)
        booleanParam(name: &apos;INIT_ENVIRONMENT_VARIABLES&apos;, defaultValue: false, description: &apos;Initialize environment variables&apos;)
        string(name: &apos&apos;, defaultValue: &apos;master&apos;, description: &apos;Specify the Git release to build&apos;)
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
        stage(&apos;Set Environment Variables&apos;) {
            when {
                expression {
                    params.INIT_ENVIRONMENT_VARIABLES == true
                }
            }
            steps {
                script {
                    def propertiesContent = sh(script: &quot;printenv&quot;, returnStdout: true)
                    writeFile file: &quot;${workspace}/../environment_variables_${env.JOB_NAME}.properties&quot;, text: propertiesContent
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

        stage(&apos;Checkout&apos;) {
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
               git branch: &quot;${params.RELEASE_TO_BUILD}&quot;, url: &quot;${GIT_REPOSITORY}&quot;
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

        stage(&apos;Package&apos;) {
            when {
                expression {
                    return !params.DEPLOY_ONLY
                }
            }
            steps {
                script {
                    sh &apos;mvn install -DskipTests=true -Dsite.skip=true&apos;

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

                    // Remove the parent module from the list (if present)
                    modules = modules.findAll { it != &apos;openmeetings-parent&apos; }

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
                                repository: PRD_NEXUS_REPOSITORY,
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

        stage(&apos;Deploy&apos;) {
            parallel {
                stage(&apos;Green&apos;) {
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
                            if (currentBuild.result == &apos;SUCCESS&apos;) {
                                def propertiesContent = setEnvironmentVariables()
                                // Write the environment variables to a file
                                writeFile file: &quot;${workspace}/../environment_variables_${env.JOB_NAME}.properties&quot;, text: propertiesContent
                        }

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
                    emailext subject: &quot;[JENKINS][${env.JOB_NAME}] ${currentBuild.result} at ${FAILED_STAGE}&quot;,
                             body: &quot;The build has failed or is unstable in stage: ${FAILED_STAGE} \nCheck console output at ${env.BUILD_URL} to view the results.&quot;,
                             to: &quot;${params.STATIC_RECIPIENTS}&quot;
                }
            }
        }
    }
}


def deployToEnvironment( String blueARN, String greenARN, String blueIP, String greenIP) {
    def deploymentCmd = getDeploymentCommand(greenIP)
    def validateCmd = getValidationCommand(blueARN,greenARN,greenIP)

    stage(&quot;Deploying&quot;) {
        script {
            withCredentials([
                sshUserPrivateKey(
                    credentialsId: APP_PRD_SSH_CREDENTIALS_ID,
                    keyFileVariable: &apos;SSH_KEY&apos;,
                    passphraseVariable: &apos;SSH_PASSPHRASE&apos;,
                    usernameVariable: &apos;SSH_USERNAME&apos;
                ),
                [
                    $class: &apos;AmazonWebServicesCredentialsBinding&apos;,
                    accessKeyVariable: &apos;AWS_ACCESS_KEY&apos;,
                    credentialsId: JENKINS_AWS_ID,
                    secretKeyVariable: &apos;AWS_SECRET_KEY&apos;
                ]
            ]) {
                sh deploymentCmd
                sleep time: 15, unit: &apos;SECONDS&apos; // Wait for 5 seconds
            }

        }
    }

    stage(&quot;Route traffic&quot;) {
        script {
            withCredentials([
                sshUserPrivateKey(
                    credentialsId: APP_PRD_SSH_CREDENTIALS_ID,
                    keyFileVariable: &apos;SSH_KEY&apos;,
                    passphraseVariable: &apos;SSH_PASSPHRASE&apos;,
                    usernameVariable: &apos;SSH_USERNAME&apos;
                ),
                [
                    $class: &apos;AmazonWebServicesCredentialsBinding&apos;,
                    accessKeyVariable: &apos;AWS_ACCESS_KEY&apos;,
                    credentialsId: JENKINS_AWS_ID,
                    secretKeyVariable: &apos;AWS_SECRET_KEY&apos;
                ]
            ]) {
                sh validateCmd
            }
        }
    }
}


def getDeploymentCommand(greenIP) {
    return &quot;&quot;&quot;
        ssh-keyscan -H ${greenIP} &gt;&gt; ~/.ssh/known_hosts
        scp -i \${SSH_KEY} -r ${workspace}/openmeetings-server/target/ ec2-user@${greenIP}:/home/ec2-user/openmeetings
        ssh -i \${SSH_KEY} ec2-user@${greenIP} &apos;sudo tar -xzf /home/ec2-user/openmeetings/target/*SNAPSHOT.tar.gz --strip-components=1 -C /home/ec2-user/openmeetings-app &amp;&amp; sudo /home/ec2-user/openmeetings-app/bin/startup.sh&apos;
    &quot;&quot;&quot;
}

def getValidationCommand(String blueARN, String greenARN, String greenIP)  {

    return &quot;&quot;&quot;
        if [ &quot;\$( curl -o /dev/null -s -I -w &apos;%{http_code}&apos; http://${greenIP}:5080/)&quot; -eq 200 ]
        then
            echo &quot;** BUILD IS SUCCESSFUL **&quot;
            curl -I http://${greenIP}:5080/
            aws configure set aws_access_key_id \$AWS_ACCESS_KEY
            aws configure set aws_secret_access_key \$AWS_SECRET_KEY
            aws configure set region us-west-2
            aws elbv2 modify-listener --listener-arn \${APP_LISTENER_ARN} --default-actions &apos;[{\&quot;Type\&quot;: \&quot;forward\&quot;,\&quot;Order\&quot;: 1,\&quot;ForwardConfig\&quot;: {\&quot;TargetGroups\&quot;: [{\&quot;TargetGroupArn\&quot;: \&quot;${blueARN}\&quot;, \&quot;Weight\&quot;: 0 },{\&quot;TargetGroupArn\&quot;: \&quot;${greenARN}\&quot;, \&quot;Weight\&quot;: 1 }],\&quot;TargetGroupStickinessConfig\&quot;: {\&quot;Enabled\&quot;: true,\&quot;DurationSeconds\&quot;: 1}}}]&apos;
        else
            echo &quot;** BUILD IS FAILED ** Health check returned non 200 status code&quot;
            curl -I http://${greenIP}:5080/
            exit 2
        fi
    &quot;&quot;&quot;
}

def loadPropertiesFromFile() {
    def propertiesFilePath = &quot;${workspace}/../environment_variables_${env.JOB_NAME}.properties&quot;
    def properties = sh(script: &quot;cat ${propertiesFilePath}&quot;, returnStdout: true).trim()

    // Create a map to store the key-value pairs from properties
    def propertiesMap = [:]

    properties.split(&apos;\n&apos;).each { property -&gt;
        def (key, value) = property.split(&apos;=&apos;)
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

    def propertiesContent = propertiesMap.collect { key, value -&gt; &quot;${key}=${value}&quot; }.join(&apos;\n&apos;)

    return propertiesContent
}