terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }
}

provider "aws" {
  region = "<{ aws-region }>"
}

resource "aws_vpc" "network" {
  cidr_block           = "<{ aws-vpc-cidr }>"
  enable_dns_hostnames = true
  tags                 = { Name = "<{ aws-name }>" }
}

resource "aws_internet_gateway" "gateway" {
  vpc_id = aws_vpc.network.id
  tags   = { Name = "<{ aws-name }>" }
}

resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.network.id
  cidr_block              = "<{ aws-subnet-cidr }>"
  availability_zone       = "<{ aws-availability-zone }>"
  map_public_ip_on_launch = true
  tags                    = { Name = "<{ aws-name }>" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.network.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.gateway.id
  }
  tags = { Name = "<{ aws-name }>" }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

resource "aws_security_group" "node1" {
  name   = "<{ aws-name }>"
  vpc_id = aws_vpc.network.id
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = { Name = "<{ aws-name }>" }
}

resource "aws_key_pair" "operator" {
  # In keygen mode the pair is named after the profile (SSH Keypair Standard);
  # key_name is unique per region and the instance depends on this resource,
  # so a colliding name fails the apply before any instance exists.
<% if ssh-keygen %>  key_name   = "<{ profile }>"
<% else %>  key_name   = "<{ aws-name }>"
<% endif %>  public_key = file("<{ aws-ssh-authorized-keys }>")
}

resource "aws_instance" "node1" {
  ami                         = "<{ aws-image-id }>"
  instance_type               = "<{ aws-instance-type }>"
  availability_zone           = "<{ aws-availability-zone }>"
  subnet_id                   = aws_subnet.public.id
  vpc_security_group_ids      = [aws_security_group.node1.id]
  associate_public_ip_address = true
  key_name                    = aws_key_pair.operator.key_name
  root_block_device {
    volume_size = <{ aws-root-volume-size-gb }>
    volume_type = "gp3"
  }
  tags = { Name = "<{ aws-name }>" }
  connection {
    type  = "ssh"
    user  = "ubuntu"
<% if ssh-keygen %>    private_key = file("<{ ssh-private-key-path }>")
<% else %>    agent = true
<% endif %>    host  = self.public_ip
  }
  provisioner "remote-exec" {
    inline = ["ls"]
  }
  lifecycle {
    prevent_destroy = <{ compute-prevent-destroy }>
  }
}

output "params" {
  value = {
    ip     = aws_instance.node1.public_ip
    sudoer = "ubuntu"
    uid    = "1000"
    name   = "<{ profile }>"
    user   = "ubuntu"
  }
}
