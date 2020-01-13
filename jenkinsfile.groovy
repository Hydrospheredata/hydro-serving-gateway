node {
  stage('SCM') {
    git 'https://github.com/Hydrospheredata/hydro-serving-gateway.git'
  }
  stage('SonarQube analysis') {
    def scannerHome = tool 'SonarScanner 4.0';
    withSonarQubeEnv('Sonarcloud') { // If you have configured more than one global server connection, you can specify its name
      sh "${scannerHome}/bin/sonar-scanner"
    }
  }

  stage("trigger-central") {
    build job: 'provectus.com/hydro-central/master', parameters: [
      [$class: 'StringParameterValue',
      name: 'PROJECT',
      value: 'gateway'
      ],
      [$class: 'StringParameterValue',
      name: 'BRANCH',
      value: env.BRANCH_NAME
      ]
    ]
  }
}
