pipeline {
    agent any

  stages {  
    stage('SCM') {
      steps {
        git 'https://github.com/Hydrospheredata/hydro-serving-gateway.git'
      }
    }

    stage("trigger-central") {
      steps {
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
  }
}
