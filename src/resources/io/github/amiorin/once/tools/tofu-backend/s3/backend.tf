terraform {
  backend "s3" {
    bucket = "<{ s3-bucket }>"
    key    = "<{ s3-key }>"
    region = "<{ s3-region }>"
  }
}
