#!/bin/bash
# 1. GDK component build

# 2. Copy over /output/ to /greengrass-build/

# 3. Run this
sudo /greengrass/v2/bin/greengrass-cli deployment create \
  --recipeDir /home/soren/projects/aws-greengrass-kvs-connector/greengrass-build/recipes \
  --artifactDir /home/soren/projects/aws-greengrass-kvs-connector/greengrass-build/artifacts \
  --update-config /home/soren/projects/aws-greengrass-kvs-connector/config.json \
  --merge "Blackbird.kvs.connector=1.0.0"

# systemctl restart greengrass for changes to take effect