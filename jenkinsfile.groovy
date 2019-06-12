node("CentralNode") {

  stage("trigger-central") {
    build job: 'provectus.com/hydro-central/master', parameters: [
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
