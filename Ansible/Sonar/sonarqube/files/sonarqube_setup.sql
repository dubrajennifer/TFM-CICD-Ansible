CREATE DATABASE sonarqube_db CHARACTER SET utf8 COLLATE utf8_general_ci;
CREATE USER 'sonarqube_user'@'localhost' IDENTIFIED BY 'sonarqube_bd_pwd';
GRANT ALL PRIVILEGES ON sonarqube_db.* TO 'sonarqube_user'@'localhost';
FLUSH PRIVILEGES;

