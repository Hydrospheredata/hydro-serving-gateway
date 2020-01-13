node {
  stage('SCM') {
    git 'https://github.com/Hydrospheredata/hydro-serving-gateway.git'
  }
  stage('SonarQube analysis') {
    def scannerHome = tool 'Sonarcloud';
    withSonarQubeEnv('Sonarcloud') { // If you have configured more than one global server connection, you can specify its name
      sh "${scannerHome}/bin/sonar-scanner \
         -Dsonar.projectKey=Hydrospheredata_hydro-serving-gateway \
         -Dsonar.organization=hydrosphere \
         -Dsonar.sources=. \
         -Dsonar.host.url=https://sonarcloud.io \
         -Dsonar.login=f4edb54bde6f29b48660b944fda885099b9a2a48"
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
