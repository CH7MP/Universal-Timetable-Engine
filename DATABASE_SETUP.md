# Database Setup Guide

## Prerequisites
- MySQL 5.7 or higher installed
- MySQL is running
- Command line access to MySQL

## ⚡ Quick Setup (2 minutes)

### Step 1: Create Database
Open terminal/command prompt and run:

```bash
mysql -u root -p
```

Enter your MySQL password, then paste:

```sql
CREATE DATABASE timetable_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
EXIT;
```

**Done!** The app will create tables automatically on first run.

---

## 🔒 Optional: Create Separate Database User

For security, create a dedicated user instead of using `root`:

```bash
mysql -u root -p
```

Then run:

```sql
CREATE USER 'timetable_user'@'localhost' IDENTIFIED BY 'secure_password_here';
GRANT ALL PRIVILEGES ON timetable_db.* TO 'timetable_user'@'localhost';
FLUSH PRIVILEGES;
EXIT;
```

Then use in app:
```
User: timetable_user
Password: secure_password_here
```

---

## 🔧 Connection Settings

In the application, these are set automatically:

| Setting | Value |
|---------|-------|
| Host | localhost |
| Port | 3306 |
| Database | timetable_db |
| User | root (or timetable_user) |
| Password | Set via DB_PASSWORD environment variable |

---

## 📊 Database Tables (Auto-Created)

The app creates these tables on first run:

### 1. generations
Stores each timetable generation
```sql
CREATE TABLE generations (
  id INT AUTO_INCREMENT PRIMARY KEY,
  created_at DATETIME NOT NULL,
  label VARCHAR(120)
);
```

### 2. subjects
Subject information
```sql
CREATE TABLE subjects (
  id INT AUTO_INCREMENT PRIMARY KEY,
  generation_id INT,
  name VARCHAR(120),
  code VARCHAR(30),
  year INT,
  lectures INT,
  has_lab TINYINT(1),
  labs INT
);
```

### 3. teachers
Teacher information
```sql
CREATE TABLE teachers (
  id INT AUTO_INCREMENT PRIMARY KEY,
  generation_id INT,
  name VARCHAR(120)
);
```

### 4. timetable
Complete timetable schedule
```sql
CREATE TABLE timetable (
  id INT AUTO_INCREMENT PRIMARY KEY,
  generation_id INT NOT NULL,
  section VARCHAR(20),
  day VARCHAR(20),
  slot INT,
  slot_label VARCHAR(60),
  subject VARCHAR(120),
  teacher VARCHAR(120),
  room VARCHAR(60),
  is_lab TINYINT(1),
  batch_no INT,
  lab_continued TINYINT(1)
);
```

---

## ✅ Verify Installation

Check if database was created:

```bash
mysql -u root -p
```

Then:
```sql
SHOW DATABASES;
```

Should show:
```
information_schema
mysql
performance_schema
timetable_db
sys
```

Check tables in timetable_db:
```sql
USE timetable_db;
SHOW TABLES;
```

After running the app, should show:
```
generations
subjects
teachers
timetable
```

---

## 🔄 Backup & Restore

### Backup Database
```bash
mysqldump -u root -p timetable_db > timetable_backup.sql
```

### Restore Database
```bash
mysql -u root -p timetable_db < timetable_backup.sql
```

### Backup All Data (with timestamps)
```bash
mysqldump -u root -p timetable_db > backup_$(date +%Y%m%d_%H%M%S).sql
```

---

## 🗑️ Delete & Recreate Database

If you want to start fresh:

```bash
mysql -u root -p
```

Then:
```sql
DROP DATABASE timetable_db;
CREATE DATABASE timetable_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
EXIT;
```

Run the app again - it will recreate all tables.

---

## 🐛 Troubleshooting

### Error: "Access denied for user 'root'@'localhost'"
- Check your MySQL password
- Make sure MySQL is running

**Windows:**
```cmd
net start MySQL80
```

**Mac:**
```bash
brew services start mysql
```

**Linux:**
```bash
sudo systemctl start mysql
```

### Error: "Can't connect to MySQL server on 'localhost'"
- MySQL is not running
- Check with: `mysql -u root -p`
- If not installed, download from https://www.mysql.com/downloads/

### Error: "Unknown database 'timetable_db'"
- Database not created yet
- Run Step 1 of Quick Setup above

### Error: "Too many connections"
- Close other MySQL connections
- Restart MySQL

---

## 📱 Remote MySQL (Advanced)

If using remote MySQL server instead of localhost:

```sql
CREATE USER 'timetable_user'@'your.server.ip' IDENTIFIED BY 'password';
GRANT ALL PRIVILEGES ON timetable_db.* TO 'timetable_user'@'your.server.ip';
FLUSH PRIVILEGES;
```

Then in app connection: use server IP instead of `localhost`

---

## 📝 Notes

- App automatically creates all required tables on first run
- Set `DB_PASSWORD` environment variable for secure password handling
- Keep backups of your database regularly
- Default MySQL port is 3306

Need help? Check the troubleshooting section above!
