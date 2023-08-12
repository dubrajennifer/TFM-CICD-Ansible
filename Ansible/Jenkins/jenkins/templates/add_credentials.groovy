import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey
import hudson.util.Secret
import groovy.yaml.YamlSlurper

def credsDomain = com.cloudbees.plugins.credentials.domains.Domain.global()
def credsStore = Jenkins.instance.getExtensionList('com.cloudbees.plugins.credentials.SystemCredentialsProvider')[0].getStore()

def credentialsFile = new File('../vars/credentials.yml')  // Replace with actual path
def credentialsData = new YamlSlurper().parse(credentialsFile.text)

for (cred in credentialsData.usernamePasswordCredentials) {
    def username = cred.username
    def password = Secret.fromString(cred.password)
    
    def usernamePasswordCredential = new UsernamePasswordCredentialsImpl(
        com.cloudbees.plugins.credentials.CredentialsScope.GLOBAL,
        cred.id,
        cred.id,
        "Username/Password credentials",
        username,
        password
    )
    
    credsStore.addCredentials(credsDomain, usernamePasswordCredential)
}

println "Credentials added successfully"
