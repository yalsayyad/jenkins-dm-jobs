def notify = null
node {
  git([
    url: 'https://github.com/lsst-sqre/jenkins-dm-jobs.git',
    branch: 'master'
  ])
  notify = load 'pipelines/lib/notify.groovy'
}

try {
  notify.started()

  stage 'generate weekly tag'

  def tz = TimeZone.getTimeZone('America/Los_Angeles')
  def dateFormat = new java.text.SimpleDateFormat('Y.w')
  dateFormat.setTimeZone(tz)
  def date = new java.util.Date()
  def dateStamp = dateFormat.format(date)

  def git_tag = "w.${dateStamp}"
  echo "generated [git] tag: ${git_tag}"


  stage 'run build-publish-tag'

  build job: 'release/build-publish-tag',
    parameters: [
      string(name: 'BRANCH', value: ''),
      string(name: 'PRODUCT', value: 'lsst_distrib'),
      string(name: 'GIT_TAG', value: git_tag),
      booleanParam(name: 'SKIP_DEMO', value: false),
      booleanParam(name: 'SKIP_DOCS', value: false)
    ]
} catch (e) {
  // If there was an exception thrown, the build failed
  currentBuild.result = "FAILED"
  throw e
} finally {
  echo "result: ${currentBuild.result}"
  switch(currentBuild.result) {
    case 'SUCCESS':
      notify.success()
      break
    case 'ABORTED':
      notify.aborted()
      break
    case 'FAILURE':
      notify.failure()
      break
    default:
      notify.failure()
  }
}