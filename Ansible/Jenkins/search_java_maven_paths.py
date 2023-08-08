import subprocess
cmd = "ls -ltra /usr/lib/jvm ; ls -ltra /opt"
sp = subprocess.Popen(cmd,shell=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,universal_newlines=True)
rc = sp.wait()
out,err=sp.communicate()
if rc == 0:
    for each_line in out.splitlines():


        if "java-11-openjdk" in each_line and "drwxr-xr-x" in each_line:
            print(f"JAVA_HOME=/usr/lib/jvm/{each_line.split()[8]}\n")

        if "apache-maven-3.9.4" in each_line and "drwxr-xr-x" in each_line:
            print(f"M2_HOME=/opt/{each_line.split()[8]}\n\nM2=/opt/{each_line.split()[8]}/bin\n")
            print("PATH=$PATH:$HOME/bin$JAVA_HOME:$M2_HOME:$M2\n\nexport PATH")

else:
    print("Commans was failed and error is ",err)