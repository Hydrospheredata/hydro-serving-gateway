pipeline {
  stages {
    stage("trigger-central") {
      build job: 'provectus/hydro-central/master', parameters: [
          [$class: 'StringParameterValue',
            name: 'repo',
            value: 'gateway'
          ],
          [$class: 'StringParameterValue',
            name: 'branch',
            value: env.BRANCH_NAME
          ]
      ]
    }
  }
}
