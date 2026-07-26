output "params" {
  value = {
    ip = "<{ no-infra-compute-ip }>"
    sudoer = "<{ no-infra-compute-sudoer }>"
    uid = "<{ no-infra-compute-uid }>"
    name = "<{ profile }>"
    user = "<{ no-infra-compute-user }>"
  }
}
