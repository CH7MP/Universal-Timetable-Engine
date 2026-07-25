# Universal Timetable Engine 📚

An automated, intelligent timetable scheduling system for college departments with concurrent lab batch support, conflict avoidance, and multiple export formats.

## ✨ Features

✅ **Smart Scheduling Algorithm**
- Concurrent lab batch scheduling (multiple batches in different labs simultaneously)
- Teacher conflict avoidance
- Balanced weekly workload distribution
- No classroom overlaps

✅ **Export Options**
- Excel export with colored formatting
- PDF printing with legend
- QR code generation for verification

✅ **Database Support**
- MySQL integration
- Generation history tracking
- Multi-section timetables (4 years, multiple divisions)

✅ **User-Friendly GUI**
- 4-step wizard interface
- Real-time configuration
- Beautiful color-coded timetables
- Instant preview

## 🛠️ Requirements

- **Java 11 or higher**
- **MySQL 5.7 or higher**
- **Maven 3.6+** (for building)

## 📦 Installation

### Step 1: Clone Repository
```bash
git clone https://github.com/CH7MP/Universal-Timetable-Engine.git)
cd Universal-Timetable-Engine
```

### Step 2: Set Up MySQL Database
```bash
mysql -u root -p
CREATE DATABASE timetable_db;
EXIT;
```

### Step 3: Build Project
```bash
mvn clean package
```

### Step 4: Set Environment Variable

**Windows (Command Prompt):**
```cmd
set DB_PASSWORD=your_mysql_password
```

**Windows (PowerShell):**
```powershell
$env:DB_PASSWORD="your_mysql_password"
```

**Linux/Mac:**
```bash
export DB_PASSWORD=your_mysql_password
```

### Step 5: Run Application
```bash
java -cp target/classes:target/dependency/* TimetableGenerator
```

## 🚀 How to Use

1. **Start Application** → Click "Get Started"
2. **Configure Department** 
   - Set number of divisions (A, B, C...)
   - Set number of subjects, teachers, classrooms, labs
   - Click "Next"
3. **Enter Details**
   - Add subject names and codes
   - Define time slots (mark breaks)
   - List teachers and their subjects
   - Click "Generate Timetable"
4. **Export** → Save as Excel or PDF

## 🗄️ Database Setup

Tables created automatically on first run:
- `generations` - Generation history
- `subjects` - Subject details
- `teachers` - Teacher information
- `timetable` - Complete schedule

See `DATABASE_SETUP.md` for detailed instructions.

## 📋 Configuration

Set the following environment variable with your MySQL password:
```
DB_PASSWORD=your_password
```

If not set, app runs without database save (you can still export to Excel/PDF).

## 📚 Libraries Used

| Library | Version | Purpose |
|---------|---------|---------|
| Apache POI | 5.2.3 | Excel export with formatting |
| ZXing | 3.5.1 | QR code generation |
| MySQL JDBC | 8.0.33 | Database connectivity |
| Java Swing | Built-in | GUI framework |


## 🔧 Troubleshooting

**"Cannot connect to database"**
- Check MySQL is running: `mysql -u root -p`
- Verify DB_PASSWORD environment variable is set
- Ensure `timetable_db` database exists

**"Missing dependencies"**
- Run: `mvn clean install`
- Check internet connection

**"Java version error"**
- Ensure Java 11+ is installed
- Check: `java -version`

**"Cannot find main class"**
- Run: `mvn clean package`
- Check Java is in PATH

## 📝 Features Explained

### Concurrent Lab Batches
When Batch 1 is doing a lab practical, other free batches can do a different lab subject in another lab room simultaneously.

### Balanced Load
The algorithm distributes lectures evenly across the week, preventing heavy days.

### No Conflicts
- Teachers cannot teach 2 classes simultaneously
- Classrooms cannot be double-booked
- Labs scheduled efficiently

## 📄 License

MIT License - See LICENSE file for details

## 👨‍💻 Author

Shiv Shingade

## 🤝 Contributing

Feel free to fork, modify, and improve!

## 📧 Contact

shivshingade18@gmail.com

---

**Status:** ✅ Production Ready  
**Last Updated:** 2025  
**Java Version:** 11+  
**Database:** MySQL 5.7+
