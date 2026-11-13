#!/bin/bash
set -e

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
    CREATE USER IF NOT EXISTS 'papazone'@'%' IDENTIFIED BY '${MYSQL_DATABASE_PASSWORD}';
    ALTER USER 'papazone'@'%' IDENTIFIED BY '${MYSQL_DATABASE_PASSWORD}';

    GRANT ALL PRIVILEGES ON papazone_customer.* TO 'papazone'@'%';
    GRANT ALL PRIVILEGES ON papazone_menu.* TO 'papazone'@'%';
    GRANT ALL PRIVILEGES ON papazone_cart.* TO 'papazone'@'%';
    GRANT ALL PRIVILEGES ON papazone_orders.* TO 'papazone'@'%';
    GRANT ALL PRIVILEGES ON papazone_payments.* TO 'papazone'@'%';
    GRANT ALL PRIVILEGES ON papazone_deliveries.* TO 'papazone'@'%';

    FLUSH PRIVILEGES;
EOSQL
