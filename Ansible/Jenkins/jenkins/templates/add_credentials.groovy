import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl
import com.cloudbees.plugins.credentials.CredentialsScope
import hudson.util.Secret

def domain = com.cloudbees.plugins.credentials.domains.Domain.global()
def credsStore = Jenkins.instance.getExtensionList('com.cloudbees.plugins.credentials.SystemCredentialsProvider')[0].getStore()

def credentials = [
    [id: "sonar-user-credentials-idn", username: "admin", password: "sonar"],
    [id: "nexus-user-credentials-id", username: "admin", password: "nexus"]
]

for (cred in credentials) {
    def username = cred.username
    def password = Secret.fromString(cred.password)

    def credImpl = new UsernamePasswordCredentialsImpl(
        CredentialsScope.GLOBAL,
        cred.id,
        cred.id,
        "Username/Password credentials",
        username,
        password
    )

    credsStore.addCredentials(domain, credImpl)
}

println "Credentials added successfully"
