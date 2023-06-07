#!/usr/bin/env bash
if grep "PATH" /root/.bash_profile
then
        sed -i '/PATH/d' /root/.bash_profile
        python3 /tmp/Jenkins_ansibe_role/search_java_maven_paths.py >> /root/.bash_profile
else

        python3 /tmp/Jenkins_ansibe_role/search_java_maven_paths.py >> /root/.bash_profile
fi