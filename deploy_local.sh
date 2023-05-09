#!/bin/bash
# 1. GDK component build

# 2. Run this
sudo /greengrass/v2/bin/greengrass-cli deployment create \
  --recipeDir /home/soren/projects/aws-greengrass-kvs-connector/greengrass-build/recipes \
  --artifactDir /home/soren/projects/aws-greengrass-kvs-connector/greengrass-build/artifacts \
  --update-config /home/soren/projects/aws-greengrass-kvs-connector/config.json \
  --merge "Blackbird.kvs.connector=1.1.0"

# 3. systemctl restart greengrass for changes to take effect
