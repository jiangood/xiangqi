#!/bin/sh
set -e
docker-compose pull
docker-compose down 
docker-compose up -d
echo 'done'
docker-compose logs -f
