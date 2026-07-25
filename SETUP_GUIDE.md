# 📝 Complete Setup Guide - How to Upload to GitHub

## 📂 Folder Structure to Create

Create this exact folder structure on your computer:

```
timetable-generator/
├── src/
│   └── main/
│       └── java/
│           └── TimetableGenerator.java (your Java file)
├── pom.xml (download from files)
├── README.md (download from files)
├── .gitignore (download from files)
├── DATABASE_SETUP.md (download from files)
└── LICENSE (download from files)
```

---

## 🔧 How to Create .gitignore

### ❓ What is .gitignore?
A file that tells Git which files to **NOT** upload to GitHub (like build files, IDE settings, etc.)

### Method 1: Download & Rename (EASIEST)
1. Download `.gitignore` file I created above
2. **Important:** Rename it from `.gitignore` to `.gitignore` (keep the dot at start)
3. Move it to your project folder

### Method 2: Create Manually (Windows)
1. Open Notepad
2. Paste the content from the `.gitignore` file above
3. Click **File → Save As**
4. Set filename to: `.gitignore`
5. Set "Save as type" to: **All Files (*.*)**
6. Save in your project folder

> ⚠️ **IMPORTANT:** File MUST start with a dot (`.gitignore`) not `gitignore`

### Method 3: Create Manually (Mac/Linux)
1. Open Terminal
2. Navigate to your project folder:
   ```bash
   cd ~/Documents/timetable-generator
   ```
3. Create the file:
   ```bash
   touch .gitignore
   ```
4. Open with text editor:
   ```bash
   nano .gitignore
   ```
5. Paste the content from the `.gitignore` file above
6. Press `Ctrl+O` then `Enter` to save
7. Press `Ctrl+X` to exit

---

## 🚀 Step-by-Step GitHub Upload

### Step 1: Install Git
- Download from: https://git-scm.com/
- Install with default options
- Restart your computer

### Step 2: Create GitHub Account
- Go to https://github.com
- Click "Sign up"
- Create account (free)

### Step 3: Create New Repository on GitHub
1. Log in to GitHub
2. Click **+** icon → **New repository**
3. Name: `timetable-generator`
4. Description: "Automated timetable scheduling system"
5. Set to **Public**
6. **DO NOT** check "Initialize with README"
7. Click **Create repository**

### Step 4: Configure Git on Your Computer
Open Command Prompt (Windows) or Terminal (Mac/Linux):

```bash
git config --global user.name "Your Name"
git config --global user.email "your.email@gmail.com"
```

### Step 5: Prepare Your Folder
1. Create folder `timetable-generator`
2. Put all files inside (see folder structure above)
3. Put your `TimetableGenerator.java` in `src/main/java/`

### Step 6: Upload to GitHub
Open Command Prompt/Terminal in your project folder:

```bash
git init
git add .
git commit -m "Initial commit: CSBS Timetable Generator"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/timetable-generator.git
git push -u origin main
```

**Replace `YOUR_USERNAME` with your actual GitHub username!**

---

## ✅ Verify Upload

1. Go to https://github.com/YOUR_USERNAME/timetable-generator
2. Should see all your files
3. Click on files to preview them
4. Done! ✨

---

## 📋 File Checklist

Before uploading, verify you have:

- [ ] pom.xml
- [ ] README.md
- [ ] .gitignore
- [ ] DATABASE_SETUP.md
- [ ] LICENSE
- [ ] TimetableGenerator.java (in src/main/java/)

---

## 🔄 After First Upload

To update your project:

```bash
git add .
git commit -m "Updated feature XYZ"
git push
```

---

## ❓ Common Issues & Fixes

### Issue: "git not recognized"
- Git not installed
- **Fix:** Restart your computer after installing Git

### Issue: "fatal: not a git repository"
- Not in correct folder
- **Fix:** Make sure you're in `timetable-generator` folder
  ```bash
  cd path/to/timetable-generator
  git status
  ```

### Issue: "Permission denied (publickey)"
- GitHub SSH key not set up
- **Fix:** Use HTTPS instead:
  ```bash
  git remote remove origin
  git remote add origin https://github.com/YOUR_USERNAME/timetable-generator.git
  git push -u origin main
  ```

### Issue: ".gitignore not working"
- File created incorrectly (missing leading dot)
- **Fix:** Recreate with exact name `.gitignore`

### Issue: "large files rejected"
- pom.xml or JAR too large
- **Fix:** Use `.gitignore` to exclude them
  ```
  target/
  *.jar
  ```

---

## 📱 Next Steps

After uploading to GitHub:

1. **Add description:** Edit Repository → About → Add description
2. **Add topics:** Add tags like: `java`, `timetable`, `scheduling`
3. **Share link:** https://github.com/YOUR_USERNAME/timetable-generator
4. **Collaborate:** Others can now fork and contribute

---

## 🎯 Your Final GitHub Link

Once uploaded: 
```
https://github.com/YOUR_USERNAME/timetable-generator
```

Share this link with anyone who wants to use your project!

---

## 💡 Pro Tips

1. **Make commits often** - Don't upload everything at once
2. **Write good commit messages** - "Fixed bug" is bad, "Fixed teacher conflict detection on Monday" is good
3. **Keep README updated** - Update as you add features
4. **Add screenshots** - Upload pictures of your GUI to README
5. **Tag releases** - When you're happy with a version, tag it as v1.0.0

---

**Questions?** Email or check GitHub Help: https://docs.github.com
