terraform {
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = ">= 4.0"
    }
  }
}

provider "azurerm" {
  subscription_id = "<{ azure-subscription-id }>"
  features {}
}

resource "azurerm_resource_group" "once" {
  name     = "<{ azure-resource-group }>"
  location = "<{ azure-location }>"
}

resource "azurerm_virtual_network" "network" {
  name                = "<{ azure-name }>"
  location            = azurerm_resource_group.once.location
  resource_group_name = azurerm_resource_group.once.name
  address_space       = ["<{ azure-vnet-cidr }>"]
}

resource "azurerm_subnet" "public" {
  name                 = "<{ azure-name }>"
  resource_group_name  = azurerm_resource_group.once.name
  virtual_network_name = azurerm_virtual_network.network.name
  address_prefixes     = ["<{ azure-subnet-cidr }>"]
}

resource "azurerm_public_ip" "node1" {
  name                = "<{ azure-name }>"
  location            = azurerm_resource_group.once.location
  resource_group_name = azurerm_resource_group.once.name
  allocation_method   = "Static"
  sku                 = "Standard"
}

resource "azurerm_network_security_group" "node1" {
  name                = "<{ azure-name }>"
  location            = azurerm_resource_group.once.location
  resource_group_name = azurerm_resource_group.once.name

  security_rule {
    name                       = "ssh"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "22"
    source_address_prefix      = "*"
    destination_address_prefix = "*"
  }
  security_rule {
    name                       = "http"
    priority                   = 110
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "80"
    source_address_prefix      = "*"
    destination_address_prefix = "*"
  }
  security_rule {
    name                       = "https"
    priority                   = 120
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "443"
    source_address_prefix      = "*"
    destination_address_prefix = "*"
  }
}

resource "azurerm_network_interface" "node1" {
  name                = "<{ azure-name }>"
  location            = azurerm_resource_group.once.location
  resource_group_name = azurerm_resource_group.once.name

  ip_configuration {
    name                          = "public"
    subnet_id                     = azurerm_subnet.public.id
    private_ip_address_allocation = "Dynamic"
    public_ip_address_id          = azurerm_public_ip.node1.id
  }
}

resource "azurerm_network_interface_security_group_association" "node1" {
  network_interface_id      = azurerm_network_interface.node1.id
  network_security_group_id = azurerm_network_security_group.node1.id
}

resource "azurerm_linux_virtual_machine" "node1" {
  name                            = "<{ azure-name }>"
  location                        = azurerm_resource_group.once.location
  resource_group_name             = azurerm_resource_group.once.name
  size                            = "<{ azure-vm-size }>"
  admin_username                  = "ubuntu"
  disable_password_authentication = true
  network_interface_ids           = [azurerm_network_interface.node1.id]

  admin_ssh_key {
    username   = "ubuntu"
    public_key = file("<{ azure-ssh-authorized-keys }>")
  }

  source_image_reference {
    publisher = "<{ azure-image-publisher }>"
    offer     = "<{ azure-image-offer }>"
    sku       = "<{ azure-image-sku }>"
    version   = "<{ azure-image-version }>"
  }

  os_disk {
    caching              = "ReadWrite"
    storage_account_type = "Standard_LRS"
    disk_size_gb         = <{ azure-boot-disk-size-gb }>
  }

  connection {
    type  = "ssh"
    user  = "ubuntu"
<% if ssh-keygen %>    private_key = file("<{ ssh-private-key-path }>")
<% else %>    agent = true
<% endif %>    host  = azurerm_public_ip.node1.ip_address
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
    ip     = azurerm_public_ip.node1.ip_address
    sudoer = "ubuntu"
    uid    = "1000"
    name   = "<{ profile }>"
    user   = "ubuntu"
  }
}
