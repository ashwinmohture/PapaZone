CREATE DATABASE IF NOT EXISTS papazone_customer CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS papazone_menu CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS papazone_cart CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS papazone_orders CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS papazone_payments CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS papazone_deliveries CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'papazone'@'%' IDENTIFIED BY 'papazone';
GRANT ALL PRIVILEGES ON papazone_customer.* TO 'papazone'@'%';
GRANT ALL PRIVILEGES ON papazone_menu.* TO 'papazone'@'%';
GRANT ALL PRIVILEGES ON papazone_cart.* TO 'papazone'@'%';
GRANT ALL PRIVILEGES ON papazone_orders.* TO 'papazone'@'%';
GRANT ALL PRIVILEGES ON papazone_payments.* TO 'papazone'@'%';
GRANT ALL PRIVILEGES ON papazone_deliveries.* TO 'papazone'@'%';
FLUSH PRIVILEGES;
