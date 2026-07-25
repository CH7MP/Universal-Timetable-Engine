import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.print.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import java.io.File;
import java.io.FileWriter;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * CSBS Department Timetable Generator
 * ─────────────────────────────────────────────────────────────────
 * SCHEDULING RULES (v3):
 *  1. Lectures fill from start of day (earliest free non-break slot).
 *     Free periods bubble to the END of each day's timetable.
 *  2. Weekly load is balanced: days with fewer lectures are preferred
 *     when placing new ones (target: totalLectures / numWorkingDays).
 *  3. Lab = exactly 2 consecutive slots per batch.
 *     Each lab subject gets ONE batch per day (different days for
 *     different batches) — never all batches stacked on one day.
 *  4. CONCURRENT LAB BATCHES (lab sessions only):
 *     When Batch X is doing a lab practical, other batches that are free
 *     in those same slots are scheduled for a DIFFERENT lab subject
 *     simultaneously (using a separate lab room). This maximises lab
 *     utilisation and keeps free batches productive.
 *     Lectures are never affected by this rule.
 *  5. Every generated timetable is saved to the DB with a
 *     generation_id (timestamp) so history is preserved.
 */
class TimetableGenerator extends JFrame {

    Connection con;

    // ═══════════════════════════════════════════════════════════
    // DATA MODELS
    // ═══════════════════════════════════════════════════════════

    static class Subject {
        String name, code;
        int year;
        int lecturesPerWeek;
        boolean hasLab;
        int labsPerWeek;
        Subject(String n, String c, int yr, int lpw, boolean hl, int lapw) {
            name=n; code=c; year=yr; lecturesPerWeek=lpw; hasLab=hl; labsPerWeek=lapw;
        }
        public String toString() { return name; }
    }

    static class Teacher {
        String name;
        List<Integer> subjectIndices = new ArrayList<>();
        int[][] busy; // busy[day][slot] = section index, -1 = free
        Teacher(String n, int days, int slots) {
            name=n; busy=new int[days][slots];
            for (int[] row : busy) Arrays.fill(row,-1);
        }
    }

    static class Room {
        String name;
        boolean isLab;
        int[][] busy;
        Room(String n, boolean lab, int days, int slots) {
            name=n; isLab=lab; busy=new int[days][slots];
            for (int[] row : busy) Arrays.fill(row,-1);
        }
    }

    static class TimeSlot {
        String label; boolean isBreak;
        TimeSlot(String l, boolean b) { label=l; isBreak=b; }
        public String toString() { return label; }
    }

    static class ConcurrentLab {
        int secIdx, day, sl1, sl2, batchNo;
        Subject subject; Teacher teacher; Room room;
        ConcurrentLab(int sec,int d,int s1,int s2,Subject sub,Teacher t,Room r,int bn){
            secIdx=sec;day=d;sl1=s1;sl2=s2;subject=sub;teacher=t;room=r;batchNo=bn;
        }
    }

    static class Assignment {
        Subject subject; Teacher teacher; Room room;
        boolean isLab; int batchNo; boolean labContinued;
        Assignment(Subject s, Teacher t, Room r, boolean lab, int bn) {
            subject=s; teacher=t; room=r; isLab=lab; batchNo=bn;
        }
    }

    static class Section {
        int year; char div;
        String label()      { return "Year "+year+" – Div "+div; }
        String shortLabel() { return "Y"+year+div; }
    }

    // ═══════════════════════════════════════════════════════════
    // APP STATE
    // ═══════════════════════════════════════════════════════════

    static final String[] DAYS = {"Monday","Tuesday","Wednesday","Thursday","Friday"};
    static final String[] DIV_LETTERS = {"A","B","C","D","E","F"};
    static final int NUM_YEARS = 4;

    int numDivisions, numSubjects, numTeachers, numSlots, numBatches, numClassrooms, numLabs;

    List<Section>  sections  = new ArrayList<>();
    List<Subject>  subjects  = new ArrayList<>();
    List<Teacher>  teachers  = new ArrayList<>();
    List<Room>     rooms     = new ArrayList<>();
    List<TimeSlot> timeSlots = new ArrayList<>();

    Assignment[][][] grid; // grid[section][day][slot]
    List<ConcurrentLab> concurrentLabs = new ArrayList<>(); // parallel lab sessions (lab-only, different batch/subject, same slots)

    // ═══════════════════════════════════════════════════════════
    // COLORS
    // ═══════════════════════════════════════════════════════════

    final Color PRIMARY    = new Color(25,  90, 190);
    final Color PRIMARY2   = new Color(15,  60, 140);
    final Color ACCENT     = new Color(230, 100,  20);
    final Color BG         = new Color(240, 244, 252);
    final Color CARD_BG    = Color.WHITE;
    final Color TEXT_DARK  = new Color(20,  28,  50);
    final Color TEXT_MUTED = new Color(110, 120, 150);
    final Color LAB_BG     = new Color(255, 241, 196);
    final Color LAB_BORDER = new Color(175, 115,   0);
    final Color LAB_CONT   = new Color(255, 249, 225);
    final Color BREAK_BG   = new Color(228, 230, 238);
    final Color BREAK_FG   = new Color(130, 135, 155);
    final Color SUCCESS    = new Color(22,  160,  80);
    final Color FREE_BG    = new Color(248, 248, 252);
    final Color FREE_FG    = new Color(190, 195, 215);

    final Color[] YEAR_BG = {
            new Color(219,234,254), new Color(209,250,229),
            new Color(254,243,199), new Color(252,231,230)
    };
    final Color[] YEAR_BD = {
            new Color(37,99,235),  new Color(16,185,129),
            new Color(217,119,6),  new Color(220,38,38)
    };






    // ═══════════════════════════════════════════════════════════
    // DB CONNECTION
    // ═══════════════════════════════════════════════════════════

    void connectDB() {
        try {
            String url  = "jdbc:mysql://localhost:3306/timetable_db";
            String user = "root";

            // Look for an environment variable named "DB_PASSWORD"
            String pass = System.getenv("DB_PASSWORD");

            // Fallback to empty if the environment variable isn't set yet
            if (pass == null) {
                pass = "";
            }

            con = DriverManager.getConnection(url, user, pass);
            ensureSchema();
            System.out.println("Database connected.");
        } catch (Exception e) {
            System.out.println("DB not available — timetable will not be saved. (" + e.getMessage() + ")");
            con = null;
        }
    }
    /**
     * Create tables if they don't exist yet.
     * The `generations` table acts as the "folder":
     *   each Generate click creates one row and all assignments reference it.
     */
    void ensureSchema() throws SQLException {
        Statement st = con.createStatement();
        st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS generations (" +
                        "  id         INT AUTO_INCREMENT PRIMARY KEY," +
                        "  created_at DATETIME NOT NULL," +
                        "  label      VARCHAR(120)" +
                        ")"
        );
        st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS subjects (" +
                        "  id              INT AUTO_INCREMENT PRIMARY KEY," +
                        "  generation_id   INT," +
                        "  name            VARCHAR(120)," +
                        "  code            VARCHAR(30)," +
                        "  year            INT," +
                        "  lectures        INT," +
                        "  has_lab         TINYINT(1)," +
                        "  labs            INT" +
                        ")"
        );
        st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS teachers (" +
                        "  id            INT AUTO_INCREMENT PRIMARY KEY," +
                        "  generation_id INT," +
                        "  name          VARCHAR(120)" +
                        ")"
        );
        st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS timetable (" +
                        "  id            INT AUTO_INCREMENT PRIMARY KEY," +
                        "  generation_id INT NOT NULL," +
                        "  section       VARCHAR(20)," +
                        "  day           VARCHAR(20)," +
                        "  slot          INT," +
                        "  slot_label    VARCHAR(60)," +
                        "  subject       VARCHAR(120)," +
                        "  teacher       VARCHAR(120)," +
                        "  room          VARCHAR(60)," +
                        "  is_lab        TINYINT(1)," +
                        "  batch_no      INT," +
                        "  lab_continued TINYINT(1)" +
                        ")"
        );
        st.close();
    }

    // generation_id for the current run (set in generate())
    int currentGenId = 0;

    // ═══════════════════════════════════════════════════════════
    // UI ROOTS
    // ═══════════════════════════════════════════════════════════

    JPanel mainPanel; CardLayout cards;

    public TimetableGenerator() {
        connectDB();
        setTitle("Universal Departmental Timetable Allocation Engine");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1050,700));
        setPreferredSize(new Dimension(1280,820));
        getContentPane().setBackground(BG);
        cards = new CardLayout();
        mainPanel = new JPanel(cards);
        mainPanel.setBackground(BG);
        mainPanel.add(buildWelcome(), "welcome");
        mainPanel.add(buildSetup(),   "setup");
        mainPanel.add(buildEntry(),   "entry");
        mainPanel.add(buildResult(),  "result");
        add(mainPanel);
        cards.show(mainPanel,"welcome");
        pack(); setLocationRelativeTo(null); setVisible(true);
    }

    // ═══════════════════════════════════════════════════════════
    // PANEL 1 — WELCOME
    // ═══════════════════════════════════════════════════════════

    JPanel buildWelcome() {
        JPanel p = new JPanel(new GridBagLayout()); p.setBackground(BG);
        JPanel card = roundCard(560,450);
        card.setLayout(new BoxLayout(card,BoxLayout.Y_AXIS));

        JLabel icon = centered(new JLabel("📚",SwingConstants.CENTER));
        icon.setFont(new Font("Dialog",Font.PLAIN,58));

        JLabel title = centered(styledLabel("Automated Departmental Engine",26,Font.BOLD,PRIMARY));
        JLabel sub1  = centered(styledLabel("Intelligent Resource Allocation System",18,Font.PLAIN,TEXT_DARK));
        JLabel sub2  = centered(styledLabel("Multi-Year  ·  Branch Agnostic  ·  Dynamic Lecture & Lab Scheduler",12,Font.PLAIN,TEXT_MUTED));

        JPanel features = new JPanel(new GridLayout(2,3,12,8));
        features.setBackground(CARD_BG); features.setAlignmentX(CENTER_ALIGNMENT);
        features.setMaximumSize(new Dimension(500,80));
        for (String f : new String[]{
                "⚡ Zero Resource Clashing",
                "📈 Faculty Comfort Index",
                "📱 QR Authenticity Shield",
                "🎯 Balanced Workload Math",
                "🔬 Parallel Lab Concurrency",
                "📊 Native Excel Blueprint"})
        {
            JLabel fl = styledLabel(f,12,Font.PLAIN,TEXT_DARK);
            fl.setBorder(new EmptyBorder(6,10,6,10));
            fl.setOpaque(true);
            fl.setBackground(new Color(240,245,255));
            features.add(fl);
        }

        JButton start = primaryButton("Get Started →",220,46);
        start.setAlignmentX(CENTER_ALIGNMENT);
        start.addActionListener(e -> cards.show(mainPanel,"setup"));

        card.add(Box.createVerticalStrut(16)); card.add(icon);
        card.add(Box.createVerticalStrut(12)); card.add(title);
        card.add(Box.createVerticalStrut(4));  card.add(sub1);
        card.add(Box.createVerticalStrut(8));  card.add(sub2);
        card.add(Box.createVerticalStrut(18)); card.add(features);
        card.add(Box.createVerticalStrut(22)); card.add(start);
        card.add(Box.createVerticalStrut(18));
        p.add(card); return p;
    }

    // ═══════════════════════════════════════════════════════════
    // PANEL 2 — SETUP
    // ═══════════════════════════════════════════════════════════

    JSpinner spDivisions,spSubjects,spTeachers,spSlots,spBatches,spClassrooms,spLabs;

    JPanel buildSetup() {
        JPanel outer = new JPanel(new GridBagLayout()); outer.setBackground(BG);
        JPanel card  = roundCard(580,570);
        card.setLayout(new BoxLayout(card,BoxLayout.Y_AXIS));

        card.add(sectionHeader2("Step 1 — Configure Department"));
        card.add(Box.createVerticalStrut(4));
        JLabel s = styledLabel("Set counts — forms will be built automatically",12,Font.PLAIN,TEXT_MUTED);
        s.setAlignmentX(LEFT_ALIGNMENT); card.add(s);
        card.add(Box.createVerticalStrut(20));

        JPanel yearRow = hRow("Years (Fixed — 1st to 4th Year)",null);
        yearRow.add(styledLabel("4  (Year 1, Year 2, Year 3, Year 4)",13,Font.BOLD,PRIMARY),BorderLayout.EAST);
        card.add(yearRow); card.add(Box.createVerticalStrut(10));

        String[] lbl = {
                "Divisions per Year  (e.g. 3 → A, B, C)",
                "Subjects (total, across all years)",
                "Number of Teachers",
                "Time Slots per Day",
                "Lab Batches per Section",
                "Number of Classrooms",
                "Number of Labs"
        };
        int[] def = {3, 6,14, 9,2,6,6};
        int[] min = {1, 1, 1, 4,1,1,1};
        int[] max = {6,50,60,16,6,20,20};
        JSpinner[] sp = new JSpinner[7];
        for (int i=0;i<7;i++) {
            sp[i]=new JSpinner(new SpinnerNumberModel(def[i],min[i],max[i],1));
            sp[i].setPreferredSize(new Dimension(80,34));
            ((JSpinner.DefaultEditor)sp[i].getEditor()).getTextField().setFont(new Font("Dialog",Font.BOLD,13));
            card.add(hRow(lbl[i],sp[i])); card.add(Box.createVerticalStrut(9));
        }
        spDivisions=sp[0]; spSubjects=sp[1]; spTeachers=sp[2];
        spSlots=sp[3]; spBatches=sp[4]; spClassrooms=sp[5]; spLabs=sp[6];

        JLabel note = styledLabel("ℹ  Total sections = 4 years × divisions  (e.g. 4×3=12 sections)  ·  Lab: 1 batch per day",11,Font.PLAIN,new Color(70,70,180));
        note.setAlignmentX(LEFT_ALIGNMENT); note.setBorder(new EmptyBorder(6,0,0,0));
        card.add(note); card.add(Box.createVerticalStrut(18));

        JPanel nav = navRow();
        JButton back = outlineButton("← Back",110,38);
        JButton next = primaryButton("Next →",130,38);
        back.addActionListener(e -> cards.show(mainPanel,"welcome"));
        next.addActionListener(e -> {
            numDivisions  = (int)spDivisions.getValue();
            numSubjects   = (int)spSubjects.getValue();
            numTeachers   = (int)spTeachers.getValue();
            numSlots      = (int)spSlots.getValue();
            numBatches    = (int)spBatches.getValue();
            numClassrooms = (int)spClassrooms.getValue();
            numLabs       = (int)spLabs.getValue();
            rebuildEntryPanel();
            cards.show(mainPanel,"entry");
        });
        nav.add(back); nav.add(Box.createHorizontalStrut(8)); nav.add(next);
        card.add(nav); card.add(Box.createVerticalStrut(10));
        outer.add(card); return outer;
    }

    // ═══════════════════════════════════════════════════════════
    // PANEL 3 — DATA ENTRY
    // ═══════════════════════════════════════════════════════════

    JPanel entryHolder, dataEntryRoot;
    JTextField[] subjName, subjCode, roomName, labName, teachName;
    JSpinner[]   subjYear, subjLPW, subjLabPW;
    JCheckBox[]  subjHasLab, slotIsBreak;
    JTextField[] slotLabel;
    JCheckBox[][] teachSubjCheck;

    JPanel buildEntry() {
        dataEntryRoot = new JPanel(new BorderLayout()); dataEntryRoot.setBackground(BG);
        entryHolder   = new JPanel(new BorderLayout()); entryHolder.setBackground(BG);
        dataEntryRoot.add(entryHolder,BorderLayout.CENTER); return dataEntryRoot;
    }

    void rebuildEntryPanel() {
        entryHolder.removeAll();
        JPanel wrap = new JPanel(); wrap.setLayout(new BoxLayout(wrap,BoxLayout.Y_AXIS));
        wrap.setBackground(BG); wrap.setBorder(new EmptyBorder(20,32,20,32));

        wrap.add(sectionHeader2("Step 2 — Enter Department Details"));
        wrap.add(Box.createVerticalStrut(4));
        JLabel hint = styledLabel("Fill in subjects, time slots, rooms/labs, and teachers below",12,Font.PLAIN,TEXT_MUTED);
        hint.setAlignmentX(LEFT_ALIGNMENT); wrap.add(hint);
        wrap.add(Box.createVerticalStrut(22));

        // ── SUBJECTS ────────────────────────────────────────────
        wrap.add(sectionHeader("Subjects  ("+numSubjects+")"));
        wrap.add(Box.createVerticalStrut(4));
        wrap.add(styledLabel("Year=0 means all years. Labs/Week = sessions per week per section.",11,Font.PLAIN,TEXT_MUTED));
        wrap.add(Box.createVerticalStrut(6));

        JPanel sh = new JPanel(new GridLayout(1,6,8,0));
        sh.setBackground(BG); sh.setAlignmentX(LEFT_ALIGNMENT); sh.setMaximumSize(new Dimension(Integer.MAX_VALUE,20));
        for (String c : new String[]{"Subject Name","Code","Year (0=all)","Lec/Week","Has Lab?","Labs/Week"})
            sh.add(colHeader(c));
        wrap.add(sh); wrap.add(Box.createVerticalStrut(4));

        String[][] defSubj = {
                {"Mathematics-I","MA101","1"},{"Physics","PH101","1"},{"C Programming","CS101","1"},{"Engineering Chemistry","CH101","1"},
                {"Mathematics-II","MA201","2"},{"Data Structures","CS201","2"},{"Digital Logic","CS202","2"},{"Statistics","MA202","2"},
                {"Algorithms","CS301","3"},{"Database Management","CS302","3"},{"Operating Systems","CS303","3"},{"Software Engineering","CS304","3"},
                {"Machine Learning","CS401","4"},{"Computer Networks","CS402","4"},{"Cloud Computing","CS403","4"},{"Project Work","CS404","4"}
        };
        boolean[] defLab = {false,true,true,true,false,true,true,false,true,true,true,false,true,false,true,false};

        subjName=new JTextField[numSubjects]; subjCode=new JTextField[numSubjects];
        subjYear=new JSpinner[numSubjects];   subjLPW=new JSpinner[numSubjects];
        subjHasLab=new JCheckBox[numSubjects]; subjLabPW=new JSpinner[numSubjects];

        JPanel sg = new JPanel(new GridLayout(numSubjects,6,8,6));
        sg.setBackground(BG); sg.setAlignmentX(LEFT_ALIGNMENT);
        for (int i=0;i<numSubjects;i++) {
            final int si=i;
            subjName[i]=styledField(i<defSubj.length?defSubj[i][0]:"Subject"+(i+1));
            subjCode[i]=styledField(i<defSubj.length?defSubj[i][1]:"SUB"+(i+1));
            int yrDef = i<defSubj.length?Integer.parseInt(defSubj[i][2]):0;
            subjYear[i]=new JSpinner(new SpinnerNumberModel(yrDef,0,4,1)); styleSpinner(subjYear[i]);
            subjLPW[i]=new JSpinner(new SpinnerNumberModel(4,1,12,1)); styleSpinner(subjLPW[i]);
            subjHasLab[i]=new JCheckBox(); subjHasLab[i].setBackground(BG);
            subjHasLab[i].setSelected(i<defLab.length&&defLab[i]);
            subjLabPW[i]=new JSpinner(new SpinnerNumberModel(1,1,5,1)); styleSpinner(subjLabPW[i]);
            subjLabPW[i].setEnabled(subjHasLab[i].isSelected());
            subjHasLab[i].addActionListener(e->subjLabPW[si].setEnabled(subjHasLab[si].isSelected()));
            JPanel cbW=new JPanel(new FlowLayout(FlowLayout.CENTER,0,5)); cbW.setBackground(BG); cbW.add(subjHasLab[i]);
            sg.add(subjName[i]); sg.add(subjCode[i]); sg.add(subjYear[i]);
            sg.add(subjLPW[i]); sg.add(cbW); sg.add(subjLabPW[i]);
        }
        wrap.add(sg); wrap.add(Box.createVerticalStrut(22));

        // ── TIME SLOTS ────────────────────────────────────────────
        wrap.add(sectionHeader("Time Slots  ("+numSlots+" per day)"));
        wrap.add(Box.createVerticalStrut(4));
        wrap.add(styledLabel("Check 'Break?' → slot is recess/lunch. Lectures fill slots top-down; free periods go to the end.",11,Font.PLAIN,TEXT_MUTED));
        wrap.add(Box.createVerticalStrut(6));

        JPanel slhdr = new JPanel(new GridLayout(1,3,8,0));
        slhdr.setBackground(BG); slhdr.setAlignmentX(LEFT_ALIGNMENT); slhdr.setMaximumSize(new Dimension(Integer.MAX_VALUE,20));
        slhdr.add(colHeader("Slot #")); slhdr.add(colHeader("Time Label")); slhdr.add(colHeader("Break?"));
        wrap.add(slhdr); wrap.add(Box.createVerticalStrut(4));

        String[]  defSlots  = {"8:00–8:50","8:50–9:40","9:40–10:30","10:30–10:45 (Recess)","10:45–11:35","11:35–12:25","12:25–1:10 (Lunch)","1:10–2:00","2:00–2:50","2:50–3:40"};
        boolean[] defBreaks = {false,false,false,true,false,false,true,false,false,false};

        slotLabel=new JTextField[numSlots]; slotIsBreak=new JCheckBox[numSlots];
        JPanel slotGrid = new JPanel(new GridLayout(numSlots,3,8,5));
        slotGrid.setBackground(BG); slotGrid.setAlignmentX(LEFT_ALIGNMENT);
        for (int i=0;i<numSlots;i++) {
            final int fi=i;
            JLabel num=styledLabel("Slot "+(i+1),12,Font.BOLD,TEXT_MUTED);
            slotLabel[i]=styledField(i<defSlots.length?defSlots[i]:"Slot "+(i+1));
            slotIsBreak[i]=new JCheckBox("Break");
            slotIsBreak[i].setBackground(BG);
            slotIsBreak[i].setFont(new Font("Dialog",Font.PLAIN,12));
            slotIsBreak[i].setForeground(new Color(150,70,0));
            if (i<defBreaks.length&&defBreaks[i]) {
                slotIsBreak[i].setSelected(true);
                slotLabel[i].setEnabled(false); slotLabel[i].setBackground(BREAK_BG);
            }
            slotIsBreak[i].addActionListener(e->{
                slotLabel[fi].setEnabled(!slotIsBreak[fi].isSelected());
                slotLabel[fi].setBackground(slotIsBreak[fi].isSelected()?BREAK_BG:Color.WHITE);
            });
            JPanel bw=new JPanel(new FlowLayout(FlowLayout.LEFT,4,4)); bw.setBackground(BG); bw.add(slotIsBreak[i]);
            slotGrid.add(num); slotGrid.add(slotLabel[i]); slotGrid.add(bw);
        }
        wrap.add(slotGrid); wrap.add(Box.createVerticalStrut(22));

        // ── CLASSROOMS ───────────────────────────────────────────
        wrap.add(sectionHeader("Classrooms  ("+numClassrooms+")"));
        wrap.add(Box.createVerticalStrut(6));
        JPanel chdr = new JPanel(new GridLayout(1,2,8,0));
        chdr.setBackground(BG); chdr.setAlignmentX(LEFT_ALIGNMENT); chdr.setMaximumSize(new Dimension(Integer.MAX_VALUE,20));
        chdr.add(colHeader("Room #")); chdr.add(colHeader("Room Name/ID"));
        wrap.add(chdr); wrap.add(Box.createVerticalStrut(4));
        roomName=new JTextField[numClassrooms];
        JPanel rg=new JPanel(new GridLayout(numClassrooms,2,8,5));
        rg.setBackground(BG); rg.setAlignmentX(LEFT_ALIGNMENT);
        for (int i=0;i<numClassrooms;i++) {
            rg.add(styledLabel("Room "+(i+1),12,Font.BOLD,TEXT_MUTED));
            roomName[i]=styledField("CR-"+(101+i)); rg.add(roomName[i]);
        }
        wrap.add(rg); wrap.add(Box.createVerticalStrut(22));

        // ── LABS ─────────────────────────────────────────────────
        wrap.add(sectionHeader("Labs  ("+numLabs+")"));
        wrap.add(Box.createVerticalStrut(6));
        JPanel lhdr = new JPanel(new GridLayout(1,2,8,0));
        lhdr.setBackground(BG); lhdr.setAlignmentX(LEFT_ALIGNMENT); lhdr.setMaximumSize(new Dimension(Integer.MAX_VALUE,20));
        lhdr.add(colHeader("Lab #")); lhdr.add(colHeader("Lab Name/ID"));
        wrap.add(lhdr); wrap.add(Box.createVerticalStrut(4));
        labName=new JTextField[numLabs];
        JPanel lg=new JPanel(new GridLayout(numLabs,2,8,5));
        lg.setBackground(BG); lg.setAlignmentX(LEFT_ALIGNMENT);
        for (int i=0;i<numLabs;i++) {
            lg.add(styledLabel("Lab "+(i+1),12,Font.BOLD,TEXT_MUTED));
            labName[i]=styledField("LAB-"+(i+1<10?"0":"")+(i+1)); lg.add(labName[i]);
        }
        wrap.add(lg); wrap.add(Box.createVerticalStrut(22));

        // ── TEACHERS ─────────────────────────────────────────────
        wrap.add(sectionHeader("Teachers  ("+numTeachers+")"));
        wrap.add(Box.createVerticalStrut(4));
        wrap.add(styledLabel("Tick which subjects each teacher can teach",11,Font.PLAIN,TEXT_MUTED));
        wrap.add(Box.createVerticalStrut(10));

        String[] defTeach = {
                "Dr. Sharma","Prof. Patel","Ms. Joshi","Mr. Mehta","Dr. Desai",
                "Prof. Gupta","Ms. Iyer","Mr. Verma","Dr. Rao","Prof. Singh",
                "Dr. Kumar","Ms. Reddy","Mr. Nair","Prof. Das","Dr. Bose",
                "Ms. Pillai","Mr. Tiwari","Dr. Agarwal","Prof. Mishra","Mr. Pandey"
        };
        teachName      = new JTextField[numTeachers];
        teachSubjCheck = new JCheckBox[numTeachers][numSubjects];

        for (int i=0;i<numTeachers;i++) {
            final int ti=i;
            JPanel tCard=new JPanel(); tCard.setLayout(new BoxLayout(tCard,BoxLayout.Y_AXIS));
            tCard.setBackground(CARD_BG);
            tCard.setBorder(BorderFactory.createCompoundBorder(
                    new LineBorder(new Color(210,215,240),1,true),
                    new EmptyBorder(10,14,10,14)));
            tCard.setAlignmentX(LEFT_ALIGNMENT);

            JPanel nameRow=new JPanel(new BorderLayout(10,0)); nameRow.setBackground(CARD_BG);
            nameRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,36));
            nameRow.add(styledLabel("T"+(i+1),12,Font.BOLD,PRIMARY),BorderLayout.WEST);
            teachName[i]=styledField(i<defTeach.length?defTeach[i]:"Teacher "+(i+1));
            nameRow.add(teachName[i],BorderLayout.CENTER);
            tCard.add(nameRow); tCard.add(Box.createVerticalStrut(8));

            JPanel cp=new JPanel(new WrapLayout(FlowLayout.LEFT,8,4));
            cp.setBackground(CARD_BG); cp.setAlignmentX(LEFT_ALIGNMENT);
            for (int j=0;j<numSubjects;j++) {
                final int sj=j;
                String sn=subjName[j].getText().trim();
                if (sn.isEmpty()) sn="Subject "+(j+1);
                JCheckBox cb=new JCheckBox(sn);
                cb.setBackground(CARD_BG); cb.setFont(new Font("Dialog",Font.PLAIN,12)); cb.setForeground(TEXT_DARK);
                if (j==(i%numSubjects)||(numSubjects>1&&j==((i+1)%numSubjects))) cb.setSelected(true);
                teachSubjCheck[ti][sj]=cb;
                subjName[j].addKeyListener(new KeyAdapter(){
                    public void keyReleased(KeyEvent e){
                        String txt=subjName[sj].getText().trim();
                        for (int t2=0;t2<numTeachers;t2++)
                            if (teachSubjCheck[t2][sj]!=null)
                                teachSubjCheck[t2][sj].setText(txt.isEmpty()?"Subject "+(sj+1):txt);
                    }
                });
                cp.add(cb);
            }
            tCard.add(cp); wrap.add(tCard); wrap.add(Box.createVerticalStrut(8));
        }

        wrap.add(Box.createVerticalStrut(20));
        JPanel nav=navRow();
        JButton back=outlineButton("← Back",110,38);
        JButton gen=primaryButton("Generate Timetable →",220,38);
        back.addActionListener(e->cards.show(mainPanel,"setup"));
        gen.addActionListener(e->{ if (collectAndGenerate()) cards.show(mainPanel,"result"); });
        nav.add(back); nav.add(Box.createHorizontalStrut(8)); nav.add(gen);
        wrap.add(nav);

        JScrollPane scroll=new JScrollPane(wrap); scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        entryHolder.add(scroll,BorderLayout.CENTER);
        entryHolder.revalidate(); entryHolder.repaint();
    }

    // ═══════════════════════════════════════════════════════════
    // PANEL 4 — RESULT
    // ═══════════════════════════════════════════════════════════

    JPanel resultHolder;

    JPanel buildResult() {
        JPanel root=new JPanel(new BorderLayout()); root.setBackground(BG);

        JPanel topBar=new JPanel(new BorderLayout());
        topBar.setBackground(PRIMARY); topBar.setBorder(new EmptyBorder(13,20,13,20));
        JLabel ttl=styledLabel("Generated Timetables",17,Font.BOLD,Color.WHITE);

        JPanel btns=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0)); btns.setBackground(PRIMARY);
        JButton editBtn   = topBtn("← Edit",              new Color(240, 244, 252), TEXT_DARK,   new Color(200, 210, 240));
        JButton regenBtn  = topBtn("↺ Regenerate",         new Color(230, 242, 255), PRIMARY,     new Color(180, 200, 240));
        JButton pdfBtn    = topBtn("🖨 Print / Save PDF",   new Color(245, 245, 247), TEXT_DARK,   new Color(210, 210, 215));
        JButton xlsxBtn   = topBtn("📊 Export to Excel",    new Color(33, 115, 70),   Color.WHITE, new Color(20, 90, 50));
        editBtn .addActionListener(e->cards.show(mainPanel,"entry"));
        regenBtn.addActionListener(e->regenerate());
        pdfBtn  .addActionListener(e->printToPdf());
        xlsxBtn .addActionListener(e->exportToExcel());
        btns.add(xlsxBtn); btns.add(pdfBtn); btns.add(editBtn); btns.add(regenBtn);
        topBar.add(ttl,BorderLayout.WEST); topBar.add(btns,BorderLayout.EAST);

        JPanel legend=new JPanel(new FlowLayout(FlowLayout.LEFT,18,6));
        legend.setBackground(new Color(232,237,255)); legend.setBorder(new EmptyBorder(0,16,0,16));
        legend.add(legendDot(new Color(214,230,255),new Color(30,80,200),"Lecture"));
        legend.add(legendDot(LAB_BG,LAB_BORDER,"Lab START (2 slots)"));
        legend.add(legendDot(LAB_CONT,LAB_BORDER,"Lab cont."));
        legend.add(legendDot(FREE_BG,FREE_FG,"Free period"));
        legend.add(legendDot(BREAK_BG,BREAK_FG,"Break"));

        resultHolder=new JPanel();
        resultHolder.setLayout(new BoxLayout(resultHolder,BoxLayout.Y_AXIS));
        resultHolder.setBackground(BG); resultHolder.setBorder(new EmptyBorder(18,18,18,18));

        JScrollPane scroll=new JScrollPane(resultHolder); scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(22);

        JPanel wrap=new JPanel(new BorderLayout()); wrap.setBackground(BG);
        wrap.add(legend,BorderLayout.NORTH); wrap.add(scroll,BorderLayout.CENTER);
        root.add(topBar,BorderLayout.NORTH); root.add(wrap,BorderLayout.CENTER);
        return root;
    }

    JButton topBtn(String t, Color bg, Color fg, Color border) {
        JButton b = new JButton(t);
        b.setFont(new Font("Dialog", Font.BOLD, 12));

        // 🎨 Explicitly force the foreground and background to prevent Look-and-Feel overrides
        b.setForeground(fg);
        b.setBackground(bg);

        // 🛠️ Structural fixes for modern buttons
        b.setOpaque(true);
        b.setBorderPainted(false);
        b.setContentAreaFilled(true);
        b.setFocusPainted(false);

        // Smooth padding and custom thin border
        b.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(border, 1, true),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)
        ));

        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // Hover animation effects
        b.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                b.setBackground(bg.brighter());
            }
            public void mouseExited(MouseEvent e) {
                b.setBackground(bg);
            }
        });

        return b;
    }

    JPanel legendDot(Color bg,Color bd,String text){
        JPanel p=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0)); p.setBackground(new Color(232,237,255));
        JPanel dot=new JPanel(); dot.setPreferredSize(new Dimension(14,14));
        dot.setBackground(bg); dot.setBorder(new LineBorder(bd,1,true));
        p.add(dot); p.add(styledLabel(text,11,Font.PLAIN,TEXT_DARK)); return p;
    }

    // ═══════════════════════════════════════════════════════════
    // INPUT COLLECTION & VALIDATION
    // ═══════════════════════════════════════════════════════════

    boolean collectAndGenerate() {
        // Build sections
        sections.clear();
        for (int yr=1;yr<=NUM_YEARS;yr++)
            for (int d=0;d<numDivisions;d++) {
                Section sec=new Section(); sec.year=yr; sec.div=DIV_LETTERS[d].charAt(0);
                sections.add(sec);
            }

        // Subjects
        subjects.clear();
        for (int i=0;i<numSubjects;i++) {
            String n=subjName[i].getText().trim(), c=subjCode[i].getText().trim();
            if (n.isEmpty()){showError("Subject name empty (row "+(i+1)+")");return false;}
            boolean hl=subjHasLab[i].isSelected();
            subjects.add(new Subject(n,c,(int)subjYear[i].getValue(),(int)subjLPW[i].getValue(),hl,hl?(int)subjLabPW[i].getValue():0));
        }

        // Time slots
        timeSlots.clear();
        for (int i=0;i<numSlots;i++) {
            boolean brk=slotIsBreak[i].isSelected();
            String l=slotLabel[i].getText().trim();
            if (l.isEmpty()) l=brk?"Break":"Slot "+(i+1);
            timeSlots.add(new TimeSlot(l,brk));
        }

        // Rooms
        rooms.clear();
        for (int i=0;i<numClassrooms;i++) {
            String n=roomName[i].getText().trim(); if (n.isEmpty()) n="CR-"+(i+1);
            rooms.add(new Room(n,false,DAYS.length,numSlots));
        }
        for (int i=0;i<numLabs;i++) {
            String n=labName[i].getText().trim(); if (n.isEmpty()) n="LAB-"+(i+1);
            rooms.add(new Room(n,true,DAYS.length,numSlots));
        }

        // Teachers
        teachers.clear();
        for (int i=0;i<numTeachers;i++) {
            String n=teachName[i].getText().trim();
            if (n.isEmpty()){showError("Teacher name empty (row "+(i+1)+")");return false;}
            Teacher t=new Teacher(n,DAYS.length,numSlots);
            for (int j=0;j<numSubjects;j++) if (teachSubjCheck[i][j].isSelected()) t.subjectIndices.add(j);
            if (t.subjectIndices.isEmpty()){showError("Teacher \""+n+"\" has no subjects assigned.");return false;}
            teachers.add(t);
        }

        // Validate: every subject has a teacher
        for (int j=0;j<subjects.size();j++) {
            boolean ok=false;
            for (Teacher t:teachers) if (t.subjectIndices.contains(j)){ok=true;break;}
            if (!ok){showError("Subject \""+subjects.get(j).name+"\" has no teacher.");return false;}
        }

        // Validate: enough consecutive non-break slots for 1 batch lab (2 slots)
        int maxRun=0,run=0;
        for (int i=0;i<numSlots;i++) {
            if (!timeSlots.get(i).isBreak){run++;maxRun=Math.max(maxRun,run);}else run=0;
        }
        boolean anyLab=false;
        for (Subject s:subjects) if (s.hasLab){anyLab=true;break;}
        if (anyLab && maxRun<2) {
            showError("Need at least 2 consecutive non-break slots for labs.");
            return false;
        }
        if (numClassrooms<1){showError("Need at least 1 classroom.");return false;}
        if (anyLab && numLabs<1){showError("Need at least 1 lab for lab subjects.");return false;}

        return generate();
    }

    // ═══════════════════════════════════════════════════════════
    // GENERATION ENGINE
    // ═══════════════════════════════════════════════════════════

    /**
     * Pre-computes, per day, the ordered list of usable (non-break) slot indices
     * in ascending order — so lectures are placed earliest-first.
     */
    List<Integer> nonBreakSlots = new ArrayList<>(); // sorted ascending

    /** Valid lab start slots: sl where sl and sl+1 are both non-break */
    List<Integer> labStartSlots = new ArrayList<>();

    boolean generate() {
        int S=sections.size();
        grid=new Assignment[S][DAYS.length][numSlots];
        concurrentLabs.clear();

        // Reset busy maps
        for (Teacher t:teachers) { t.busy=new int[DAYS.length][numSlots]; for (int[] r:t.busy) Arrays.fill(r,-1); }
        for (Room r:rooms)       { r.busy=new int[DAYS.length][numSlots]; for (int[] rr:r.busy) Arrays.fill(rr,-1); }

        // Compute helper slot lists
        nonBreakSlots.clear();
        for (int sl=0;sl<numSlots;sl++) if (!timeSlots.get(sl).isBreak) nonBreakSlots.add(sl);

        labStartSlots.clear();
        for (int sl=0;sl<numSlots-1;sl++)
            if (!timeSlots.get(sl).isBreak && !timeSlots.get(sl+1).isBreak)
                labStartSlots.add(sl);

        List<Room> classRooms=new ArrayList<>(), labRooms=new ArrayList<>();
        for (Room r:rooms) { if (r.isLab) labRooms.add(r); else classRooms.add(r); }

        // ── day-load tracker ──────────────────────────────────
        // dayLoad[secIdx][day] = number of lectures/labs placed on that day
        int[][] dayLoad = new int[S][DAYS.length];

        Random rng = new Random();

        // ─────────────────────────────────────────────────────────
        // STEP 1: Schedule LABS
        // Rule: each lab subject needs `labsPerWeek` sessions per section.
        //       Each session = 2 consecutive slots for EXACTLY ONE batch.
        //       Different batches go on DIFFERENT days (for the same subject).
        //
        // CONCURRENT BATCH RULE (lab only):
        //   When Batch X is placed in slots [sl1, sl2] for subject A,
        //   any other batch that is FREE in those same slots is assigned
        //   a DIFFERENT lab subject concurrently (different lab room).
        //   This means two lab sessions run in parallel — one per lab room —
        //   so no batch sits idle during another batch's practical.
        //   Lectures are never affected by this rule.
        // ─────────────────────────────────────────────────────────

        // Track per-section: which lab subjects each batch has already been assigned
        // batchLabDone[secIdx][batchNo-1] = set of subject indices already scheduled
        @SuppressWarnings("unchecked")
        Set<Integer>[][] batchLabDone = new Set[S][numBatches];
        for (int sec=0;sec<S;sec++)
            for (int b=0;b<numBatches;b++)
                batchLabDone[sec][b]=new HashSet<>();

        // Collect all lab subjects up front for concurrent filling
        List<Integer> labSubjectIndices=new ArrayList<>();
        for (int si=0;si<subjects.size();si++) if (subjects.get(si).hasLab) labSubjectIndices.add(si);

        for (int si:labSubjectIndices) {
            Subject s=subjects.get(si);
            final int subjIdx=si;
            List<Teacher> capable=capableTeachers(subjIdx);
            if (capable.isEmpty()) continue;

            List<Integer> targetSecs=new ArrayList<>();
            for (int sec=0;sec<S;sec++) {
                if (s.year==0 || s.year==sections.get(sec).year) targetSecs.add(sec);
            }

            for (int secIdx:targetSecs) {
                Set<Integer> usedDays=new HashSet<>();

                for (int sess=0;sess<s.labsPerWeek;sess++) {
                    int batchNo=(sess % numBatches)+1; // primary batch: 1, 2, ... cycling

                    List<Integer> dayOrder=new ArrayList<>();
                    for (int d=0;d<DAYS.length;d++) dayOrder.add(d);
                    dayOrder.sort((a,b2)->{
                        boolean au=usedDays.contains(a), bu=usedDays.contains(b2);
                        if (au!=bu) return au?1:-1;
                        return dayLoad[secIdx][a]-dayLoad[secIdx][b2];
                    });

                    boolean placed=false;
                    for (int d:dayOrder) {
                        if (placed) break;
                        for (int startSl:labStartSlots) {
                            int sl1=startSl, sl2=startSl+1;
                            if (grid[secIdx][d][sl1]!=null || grid[secIdx][d][sl2]!=null) continue;

                            // Find a free teacher for primary batch
                            List<Teacher> shuffT=new ArrayList<>(capable);
                            Collections.shuffle(shuffT,rng);
                            Teacher chosenT=null;
                            for (Teacher t:shuffT) {
                                if (t.busy[d][sl1]==-1 && t.busy[d][sl2]==-1){chosenT=t;break;}
                            }
                            if (chosenT==null) continue;

                            // Find a free lab room for primary batch
                            List<Room> shuffR=new ArrayList<>(labRooms);
                            Collections.shuffle(shuffR,rng);
                            Room chosenR=null;
                            for (Room r:shuffR) {
                                if (r.busy[d][sl1]==-1 && r.busy[d][sl2]==-1){chosenR=r;break;}
                            }
                            if (chosenR==null) continue;

                            // ── Commit primary batch ──────────────────────────
                            Assignment a1=new Assignment(s,chosenT,chosenR,true,batchNo);
                            Assignment a2=new Assignment(s,chosenT,chosenR,true,batchNo);
                            a2.labContinued=true;
                            grid[secIdx][d][sl1]=a1; grid[secIdx][d][sl2]=a2;
                            chosenT.busy[d][sl1]=secIdx; chosenT.busy[d][sl2]=secIdx;
                            chosenR.busy[d][sl1]=secIdx; chosenR.busy[d][sl2]=secIdx;
                            dayLoad[secIdx][d]+=2;
                            usedDays.add(d);
                            batchLabDone[secIdx][batchNo-1].add(subjIdx);
                            placed=true;

                            // ── CONCURRENT: fill other free batches with a different lab subject ──
                            // This runs in parallel — same slots, different room, different subject.
                            for (int otherBatch=1;otherBatch<=numBatches;otherBatch++) {
                                if (otherBatch==batchNo) continue;

                                // Find a different lab subject this batch hasn't done yet
                                Integer concSubjIdx=null;
                                List<Integer> candidateSubjs=new ArrayList<>(labSubjectIndices);
                                Collections.shuffle(candidateSubjs,rng);
                                for (int csi:candidateSubjs) {
                                    if (csi==subjIdx) continue; // must be a DIFFERENT subject
                                    Subject cs=subjects.get(csi);
                                    // Must apply to this section's year
                                    if (cs.year!=0 && cs.year!=sections.get(secIdx).year) continue;
                                    // Batch must not have already been assigned this lab subject
                                    if (batchLabDone[secIdx][otherBatch-1].contains(csi)) continue;
                                    // Must have a capable teacher free in these slots
                                    boolean teacherFree=false;
                                    for (Teacher ct:capableTeachers(csi)) {
                                        if (ct.busy[d][sl1]==-1 && ct.busy[d][sl2]==-1){teacherFree=true;break;}
                                    }
                                    if (!teacherFree) continue;
                                    concSubjIdx=csi;
                                    break;
                                }
                                if (concSubjIdx==null) continue; // no suitable concurrent subject found

                                Subject concSubj=subjects.get(concSubjIdx);
                                List<Teacher> concCapable=capableTeachers(concSubjIdx);

                                // Find a free teacher for concurrent batch (different from primary teacher)
                                List<Teacher> shuffT2=new ArrayList<>(concCapable);
                                Collections.shuffle(shuffT2,rng);
                                Teacher concT=null;
                                for (Teacher t:shuffT2) {
                                    if (t.busy[d][sl1]==-1 && t.busy[d][sl2]==-1){concT=t;break;}
                                }
                                if (concT==null) continue;

                                // Find a DIFFERENT free lab room
                                List<Room> shuffR2=new ArrayList<>(labRooms);
                                Collections.shuffle(shuffR2,rng);
                                Room concR=null;
                                for (Room r:shuffR2) {
                                    if (r==chosenR) continue; // must be a different room
                                    if (r.busy[d][sl1]==-1 && r.busy[d][sl2]==-1){concR=r;break;}
                                }
                                if (concR==null) continue;

                                // ── Commit concurrent batch ───────────────────
                                // Stored in concurrentLabs list (separate from main grid)
                                // so the primary batch cell stays clean and concurrent info
                                // is overlaid in the renderer and tooltip.
                                concurrentLabs.add(new ConcurrentLab(secIdx,d,sl1,sl2,
                                        concSubj,concT,concR,otherBatch));
                                concT.busy[d][sl1]=secIdx; concT.busy[d][sl2]=secIdx;
                                concR.busy[d][sl1]=secIdx; concR.busy[d][sl2]=secIdx;
                                dayLoad[secIdx][d]+=2;
                                batchLabDone[secIdx][otherBatch-1].add(concSubjIdx);
                            }

                            break; // slot placed
                        }
                    }
                }
            }
        }

        // ─────────────────────────────────────────────────────────
        // STEP 2: Schedule LECTURES
        // Rule: fill from earliest non-break slot each day.
        //       Prefer days with fewer lectures (balanced weekly load).
        //       Free periods naturally fall to the end of the day.
        // ─────────────────────────────────────────────────────────

        // Target lectures per day (approximate balance)
        // Build a work list: one entry per (section, subject, lecture-instance)
        for (int si=0;si<subjects.size();si++) {
            Subject s=subjects.get(si);
            List<Teacher> cap=capableTeachers(si);

            List<Integer> targetSecs=new ArrayList<>();
            for (int sec=0;sec<S;sec++) {
                if (s.year==0||s.year==sections.get(sec).year) targetSecs.add(sec);
            }

            for (int secIdx:targetSecs) {
                for (int lec=0;lec<s.lecturesPerWeek;lec++) {

                    // Build candidate (day, slot) pairs ordered by:
                    //   1. day load ascending (balance across week)
                    //   2. slot index ascending (fill from start of day)
                    // Only consider slots that are currently free
                    List<int[]> candidates=new ArrayList<>();
                    for (int d=0;d<DAYS.length;d++)
                        for (int sl:nonBreakSlots)
                            if (grid[secIdx][d][sl]==null)
                                candidates.add(new int[]{d,sl,dayLoad[secIdx][d]});

                    // Sort: day load asc, then slot asc (earliest in day)
                    candidates.sort((a,b)->{
                        if (a[2]!=b[2]) return a[2]-b[2]; // day load
                        return a[1]-b[1]; // slot index (earlier first)
                    });

                    boolean placed=false;
                    for (int[] cand:candidates) {
                        int d=cand[0], sl=cand[1];
                        if (grid[secIdx][d][sl]!=null) continue; // re-check (may have been filled)

                        // Find free teacher
                        Teacher chosenT=null;
                        List<Teacher> shuffT=new ArrayList<>(cap);
                        Collections.shuffle(shuffT,rng);
                        for (Teacher t:shuffT) {
                            if (t.busy[d][sl]==-1){chosenT=t;break;}
                        }
                        if (chosenT==null) continue;

                        // Find free classroom
                        Room chosenR=null;
                        List<Room> shuffR=new ArrayList<>(classRooms);
                        Collections.shuffle(shuffR,rng);
                        for (Room r:shuffR) {
                            if (r.busy[d][sl]==-1){chosenR=r;break;}
                        }
                        if (chosenR==null) continue;

                        grid[secIdx][d][sl]=new Assignment(s,chosenT,chosenR,false,0);
                        chosenT.busy[d][sl]=secIdx;
                        chosenR.busy[d][sl]=secIdx;
                        dayLoad[secIdx][d]++;
                        placed=true;
                        break;
                    }
                }
            }
        }

        // ─────────────────────────────────────────────────────────
        // STEP 3: COMPACT — shift all assignments to earliest free
        //         non-break slots (so free periods fall to day end)
        // ─────────────────────────────────────────────────────────
        compactToFront();

        renderResult();
        saveToDatabase();
        return true;
    }

    /**
     * For each section+day, collect all assignments in non-break order,
     * clear the non-break slots, then re-place assignments starting from
     * the earliest non-break slot. Break slots are never touched.
     * This guarantees lectures are at the front, free periods at the end.
     */
    void compactToFront() {

        int S = sections.size();

        for (int sec = 0; sec < S; sec++) {

            for (int d = 0; d < DAYS.length; d++) {

                class SlotEntry {
                    int slot;
                    Assignment assignment;
                    SlotEntry(int slot, Assignment assignment) { this.slot = slot; this.assignment = assignment; }
                }

                List<SlotEntry> collected = new ArrayList<>();
                for (int sl : nonBreakSlots) {
                    if (grid[sec][d][sl] != null) {
                        collected.add(new SlotEntry(sl, grid[sec][d][sl]));
                    }
                    grid[sec][d][sl] = null;
                }

                java.util.Map<Integer,Integer> labSlotMap = new java.util.HashMap<>();
                int ptr = 0;

                for (int i = 0; i < collected.size(); i++) {
                    Assignment a = collected.get(i).assignment;

                    // LAB START
                    if (a.isLab && !a.labContinued) {
                        boolean placed = false;
                        int oldStartSlot = collected.get(i).slot;

                        while (ptr < nonBreakSlots.size() - 1) {
                            int sl1 = nonBreakSlots.get(ptr);
                            int sl2 = nonBreakSlots.get(ptr + 1);

                            // Ensure REAL consecutive slots
                            if (sl2 == sl1 + 1 &&
                                    !timeSlots.get(sl1).isBreak &&
                                    !timeSlots.get(sl2).isBreak) {

                                grid[sec][d][sl1] = a;

                                // continuation
                                if (i + 1 < collected.size()) {
                                    Assignment next = collected.get(i + 1).assignment;
                                    if (next.isLab &&
                                            next.labContinued &&
                                            next.subject == a.subject &&
                                            next.batchNo == a.batchNo) {

                                        grid[sec][d][sl2] = next;
                                        i++;
                                    }
                                }

                                labSlotMap.put(oldStartSlot, sl1);
                                ptr += 2;
                                placed = true;
                                break;
                            }
                            ptr++;
                        }

                        if (!placed) {
                            System.out.println("Could not place continuous lab.");
                        }
                    }

                    // NORMAL LECTURE
                    else if (!a.labContinued) {
                        while (ptr < nonBreakSlots.size()) {
                            int sl = nonBreakSlots.get(ptr);
                            if (grid[sec][d][sl] == null) {
                                grid[sec][d][sl] = a;
                                ptr++;
                                break;
                            }
                            ptr++;
                        }
                    }
                }

                for (ConcurrentLab cl : concurrentLabs) {
                    if (cl.secIdx == sec && cl.day == d) {
                        Integer newSl1 = labSlotMap.get(cl.sl1);
                        if (newSl1 != null) {
                            cl.sl1 = newSl1;
                            cl.sl2 = newSl1 + 1;
                        }
                    }
                }
            }
        }
    }

    List<Teacher> capableTeachers(int subjIdx) {
        List<Teacher> list=new ArrayList<>();
        for (Teacher t:teachers) if (t.subjectIndices.contains(subjIdx)) list.add(t);
        return list;
    }

    void regenerate() {
        for (Teacher t:teachers){t.busy=new int[DAYS.length][numSlots];for(int[] r:t.busy) Arrays.fill(r,-1);}
        for (Room r:rooms){r.busy=new int[DAYS.length][numSlots];for(int[] rr:r.busy) Arrays.fill(rr,-1);}
        generate();
    }

    // ═══════════════════════════════════════════════════════════
    // DATABASE SAVE — stores every generation with a unique ID
    // ═══════════════════════════════════════════════════════════

    void saveToDatabase() {
        if (con==null) {
            System.out.println("DB unavailable — skipping save.");
            return;
        }
        try {
            // 1. Create a generation record (acts as the "folder")
            PreparedStatement genPs=con.prepareStatement(
                    "INSERT INTO generations(created_at, label) VALUES(NOW(), ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            String label="Generated "+new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
            genPs.setString(1,label);
            genPs.executeUpdate();
            ResultSet genKeys=genPs.getGeneratedKeys();
            genKeys.next();
            currentGenId=genKeys.getInt(1);
            genPs.close();

            // 2. Save subjects for this generation
            PreparedStatement sps=con.prepareStatement(
                    "INSERT INTO subjects(generation_id,name,code,year,lectures,has_lab,labs) VALUES(?,?,?,?,?,?,?)"
            );
            for (Subject s:subjects) {
                sps.setInt(1,currentGenId);
                sps.setString(2,s.name); sps.setString(3,s.code);
                sps.setInt(4,s.year); sps.setInt(5,s.lecturesPerWeek);
                sps.setBoolean(6,s.hasLab); sps.setInt(7,s.labsPerWeek);
                sps.executeUpdate();
            }
            sps.close();

            // 3. Save teachers for this generation
            PreparedStatement tps=con.prepareStatement(
                    "INSERT INTO teachers(generation_id,name) VALUES(?,?)"
            );
            for (Teacher t:teachers) {
                tps.setInt(1,currentGenId); tps.setString(2,t.name); tps.executeUpdate();
            }
            tps.close();

            // 4. Save full timetable grid
            PreparedStatement ps=con.prepareStatement(
                    "INSERT INTO timetable(generation_id,section,day,slot,slot_label,subject,teacher,room,is_lab,batch_no,lab_continued) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?)"
            );
            for (int sec=0;sec<sections.size();sec++) {
                for (int d=0;d<DAYS.length;d++) {
                    for (int sl=0;sl<numSlots;sl++) {
                        Assignment a=grid[sec][d][sl];
                        ps.setInt(1,currentGenId);
                        ps.setString(2,sections.get(sec).shortLabel());
                        ps.setString(3,DAYS[d]);
                        ps.setInt(4,sl);
                        ps.setString(5,timeSlots.get(sl).label);
                        if (a!=null) {
                            ps.setString(6,a.subject.code);
                            ps.setString(7,a.teacher.name);
                            ps.setString(8,a.room!=null?a.room.name:"N/A");
                            ps.setBoolean(9,a.isLab);
                            ps.setInt(10,a.batchNo);
                            ps.setBoolean(11,a.labContinued);
                        } else {
                            ps.setString(6,"FREE"); ps.setString(7,"");
                            ps.setString(8,""); ps.setBoolean(9,false);
                            ps.setInt(10,0); ps.setBoolean(11,false);
                        }
                        ps.executeUpdate();
                    }
                }
            }
            ps.close();

            // 5. Save concurrent lab sessions
            if (!concurrentLabs.isEmpty()) {
                PreparedStatement clps=con.prepareStatement(
                        "INSERT INTO timetable(generation_id,section,day,slot,slot_label,subject,teacher,room,is_lab,batch_no,lab_continued) " +
                                "VALUES(?,?,?,?,?,?,?,?,?,?,?)"
                );
                for (ConcurrentLab cl:concurrentLabs) {
                    for (int slOffset=0;slOffset<2;slOffset++) {
                        int sl=slOffset==0?cl.sl1:cl.sl2;
                        clps.setInt(1,currentGenId);
                        clps.setString(2,sections.get(cl.secIdx).shortLabel());
                        clps.setString(3,DAYS[cl.day]);
                        clps.setInt(4,sl);
                        clps.setString(5,timeSlots.get(sl).label+" [CONCURRENT]");
                        clps.setString(6,cl.subject.code);
                        clps.setString(7,cl.teacher.name);
                        clps.setString(8,cl.room!=null?cl.room.name:"N/A");
                        clps.setBoolean(9,true);
                        clps.setInt(10,cl.batchNo);
                        clps.setBoolean(11,slOffset==1);
                        clps.executeUpdate();
                    }
                }
                clps.close();
            }

            System.out.println("Timetable saved — generation_id="+currentGenId+" ("+label+")");


        } catch (Exception e) {
            System.out.println("DB save error: "+e.getMessage());
            e.printStackTrace();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // RENDER RESULT
    // ═══════════════════════════════════════════════════════════

    void renderResult() {
        resultHolder.removeAll();
        // 📱 Native QR Code Display
        try {
            // Dynamically build the text using active data instead of raw variables
            String qrText = "🏫 Universal TIMETABLE SYSTEM\n" +
                    "-------------------------\n" +
                    "🔹 Status: Officially Generated\n" +
                    "🔹 Total Slots: " + numSlots + "\n" +
                    "🔹 Active Batches: " + (sections != null ? sections.size() : "Standard") + "\n" +
                    "🔹 System Stamp: " + new java.text.SimpleDateFormat("dd-MM-yyyy HH:mm").format(new java.util.Date()) + "\n" +
                    "-------------------------\n" +
                    "Scan verified on desktop app.";
            ImageIcon qrIcon = generateQRCodeIcon(qrText, 150, 150);

            if (qrIcon != null) {
                JPanel qrPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 10));

                // Match your classic background variable if CARD_BG isn't found,
                // otherwise standard Color.white works perfectly here
                qrPanel.setBackground(java.awt.Color.WHITE);

                JLabel qrLabel = new JLabel(qrIcon);
                JLabel qrTextLabel = new JLabel("📱 Scan Schedule Blueprint Metadata");
                qrTextLabel.setFont(new Font("Dialog", Font.BOLD, 12));

                qrPanel.add(qrLabel);
                qrPanel.add(qrTextLabel);

                resultHolder.add(qrPanel);
                resultHolder.add(Box.createVerticalStrut(15));
            }
        } catch (Exception e) {
            System.err.println("QR display skipped: " + e.getMessage());
        }

        // 📊 Soft Constraint / Faculty Satisfaction Analytics Label
        try {
            int satisfactionScore = calculateFacultySatisfaction();

            JPanel scorePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));
            scorePanel.setBackground(java.awt.Color.WHITE);

            String analysisMessage = satisfactionScore > 85 ? " (Excellent Distribution)" : " (Contains minor schedule gaps)";
            JLabel scoreLabel = new JLabel("💡 Faculty Satisfaction Index: " + satisfactionScore + "%" + analysisMessage);
            scoreLabel.setFont(new Font("Dialog", Font.BOLD, 12));

            // Dynamic coloring based on how optimal the schedule is
            if (satisfactionScore > 85) {
                scoreLabel.setForeground(new java.awt.Color(46, 125, 50)); // Green
            } else if (satisfactionScore > 70) {
                scoreLabel.setForeground(new java.awt.Color(216, 111, 0)); // Orange
            } else {
                scoreLabel.setForeground(java.awt.Color.RED);
            }

            scorePanel.add(scoreLabel);
            resultHolder.add(scorePanel);
            resultHolder.add(Box.createVerticalStrut(10));

        } catch (Exception e) {
            System.err.println("Satisfaction score display skipped: " + e.getMessage());
        }
        // Show DB save status
        if (con!=null && currentGenId>0) {
            JLabel saved=styledLabel("  ✅  Saved to database — Generation #"+currentGenId,12,Font.BOLD,SUCCESS);
            saved.setAlignmentX(LEFT_ALIGNMENT);
            saved.setBorder(new EmptyBorder(0,4,12,4));
            resultHolder.add(saved);
        }

        for (int yr=1;yr<=NUM_YEARS;yr++) {
            final int year=yr;
            Color yrBg=YEAR_BG[(yr-1)%YEAR_BG.length];
            Color yrBd=YEAR_BD[(yr-1)%YEAR_BD.length];

            JLabel yrHdr = styledLabel("Year " + yr + " — Department Master Schedule", 16, Font.BOLD, yrBd);
            yrHdr.setAlignmentX(LEFT_ALIGNMENT);
            yrHdr.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0,5,0,0,yrBd),
                    new EmptyBorder(4,10,4,0)));
            resultHolder.add(yrHdr); resultHolder.add(Box.createVerticalStrut(10));

            for (int d=0;d<numDivisions;d++) {
                int secIdx=(yr-1)*numDivisions+d;
                if (secIdx>=sections.size()) continue;
                Section sec=sections.get(secIdx);

                JPanel card=new JPanel(); card.setLayout(new BoxLayout(card,BoxLayout.Y_AXIS));
                card.setBackground(CARD_BG);
                card.setBorder(BorderFactory.createCompoundBorder(
                        new LineBorder(yrBd,2,true), new EmptyBorder(0,0,14,0)));
                card.setAlignmentX(LEFT_ALIGNMENT);

                // Card header with load summary
                JPanel hdr=new JPanel(new BorderLayout());
                hdr.setBackground(yrBg); hdr.setBorder(new EmptyBorder(9,16,9,16));
                JLabel hdrLbl=new JLabel(sec.label()+"   |   "+numBatches+" Lab Batches per Subject");
                hdrLbl.setFont(new Font("Dialog",Font.BOLD,13)); hdrLbl.setForeground(yrBd);

                // Load per day summary
                int[] loads=new int[DAYS.length];
                for (int dy=0;dy<DAYS.length;dy++)
                    for (int sl:nonBreakSlots) if (grid[secIdx][dy][sl]!=null) loads[dy]++;
                StringBuilder loadSb=new StringBuilder("  Daily load: ");
                for (int dy=0;dy<DAYS.length;dy++)
                    loadSb.append(DAYS[dy].substring(0,3)).append(":").append(loads[dy]).append("  ");
                JLabel loadLbl=styledLabel(loadSb.toString(),10,Font.PLAIN,yrBd.darker());
                hdr.add(hdrLbl,BorderLayout.WEST); hdr.add(loadLbl,BorderLayout.EAST);
                card.add(hdr); card.add(Box.createVerticalStrut(8));

                // Table
                String[] cols=new String[DAYS.length+1];
                cols[0]="Time Slot"; System.arraycopy(DAYS,0,cols,1,DAYS.length);
                Object[][] data=new Object[numSlots][DAYS.length+1];
                for (int sl=0;sl<numSlots;sl++) {
                    data[sl][0]=timeSlots.get(sl);
                    for (int dy=0;dy<DAYS.length;dy++) data[sl][dy+1]=grid[secIdx][dy][sl];
                }

                final int SI=secIdx;
                JTable table=new JTable(data,cols){
                    public boolean isCellEditable(int r,int c){return false;}
                    public String getToolTipText(MouseEvent e){
                        int r=rowAtPoint(e.getPoint()),c=columnAtPoint(e.getPoint());
                        if (c>0&&r>=0&&r<numSlots) {
                            if (timeSlots.get(r).isBreak) return "Break / Recess";
                            Assignment a=grid[SI][c-1][r];
                            StringBuilder tip=new StringBuilder("<html>");
                            if (a!=null){
                                String type=a.isLab?"LAB — Batch "+a.batchNo+(a.labContinued?" (2nd slot)":" (1st slot)"):"Lecture";
                                String room=a.room!=null?"  Room: "+a.room.name:"";
                                tip.append("<b>").append(a.subject.name).append("</b><br>")
                                        .append(a.teacher.name).append("<br><i>").append(type).append("</i>").append(room);
                            }
                            // Show concurrent labs for this slot
                            for (ConcurrentLab cl:concurrentLabs) {
                                if (cl.secIdx==SI && cl.day==c-1 && (cl.sl1==r||cl.sl2==r)) {
                                    String clType="LAB — Batch "+cl.batchNo+(cl.sl2==r?" (2nd slot)":" (1st slot)")+" [CONCURRENT]";
                                    if (tip.length()>6) tip.append("<hr>");
                                    tip.append("<b>").append(cl.subject.name).append("</b><br>")
                                            .append(cl.teacher.name).append("<br><i>").append(clType).append("</i>")
                                            .append(cl.room!=null?"  Room: "+cl.room.name:"");
                                }
                            }
                            if (tip.length()==6) { tip.append("Free Period"); }
                            tip.append("</html>");
                            return tip.toString();
                        }
                        return null;
                    }
                };

                table.setRowHeight(54);
                table.getTableHeader().setFont(new Font("Dialog",Font.BOLD,12));
                table.getTableHeader().setBackground(new Color(238,242,255));
                table.getTableHeader().setForeground(TEXT_DARK);
                table.setShowGrid(true); table.setGridColor(new Color(222,226,245));
                table.setSelectionBackground(yrBg);
                table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
                for (int sl=0;sl<numSlots;sl++)
                    if (timeSlots.get(sl).isBreak) table.setRowHeight(sl,22);

                table.setDefaultRenderer(Object.class,new DefaultTableCellRenderer(){
                    public Component getTableCellRendererComponent(JTable tbl,Object val,boolean sel,boolean foc,int row,int col){
                        JLabel lbl=(JLabel)super.getTableCellRendererComponent(tbl,val,sel,foc,row,col);
                        lbl.setHorizontalAlignment(CENTER); lbl.setBorder(new EmptyBorder(4,4,4,4)); lbl.setOpaque(true);

                        if (timeSlots.get(row).isBreak) {
                            lbl.setText(col==0?"☕ "+((TimeSlot)val).label+"  [Break]":"— Break —");
                            lbl.setBackground(BREAK_BG); lbl.setForeground(BREAK_FG);
                            lbl.setFont(new Font("Dialog",Font.ITALIC,11)); return lbl;
                        }
                        if (col==0) {
                            lbl.setText(val==null?"":((TimeSlot)val).label);
                            lbl.setFont(new Font("Dialog",Font.BOLD,11));
                            lbl.setBackground(new Color(246,248,255)); lbl.setForeground(TEXT_MUTED); return lbl;
                        }
                        Assignment a=(Assignment)val;
                        if (a==null) {
                            // Free period (pushed to end of day)
                            lbl.setText("○ Free"); lbl.setForeground(FREE_FG); lbl.setBackground(FREE_BG);
                            lbl.setFont(new Font("Dialog",Font.ITALIC,11));
                        } else if (a.isLab&&a.labContinued) {
                            String rStr=a.room!=null?" ("+a.room.name+")":"";
                            lbl.setText("<html><center>"
                                    +"<span style='font-size:11px;color:#9a6800'>↑</span><br>"
                                    +"<b style='font-size:10px;color:#7a5000'>"+a.subject.code+"</b><br>"
                                    +"<span style='font-size:9px;color:#a07000'>Batch "+a.batchNo+" cont."+rStr+"</span>"
                                    +"</center></html>");
                            lbl.setBackground(LAB_CONT);
                            lbl.setBorder(BorderFactory.createCompoundBorder(
                                    BorderFactory.createMatteBorder(0,3,3,3,LAB_BORDER),
                                    new EmptyBorder(2,3,2,3)));
                        } else if (a.isLab) {
                            String rStr=a.room!=null?" "+a.room.name:"";
                            // Check for a concurrent lab in this slot
                            String concInfo="";
                            for (ConcurrentLab cl:concurrentLabs) {
                                if (cl.secIdx==SI && cl.day==col-1 && cl.sl1==row) {
                                    concInfo="<br><span style='font-size:8px;color:#2e7d32'>+B"+cl.batchNo+": "+cl.subject.code+" ("+cl.room.name+")</span>";
                                    break;
                                }
                            }
                            lbl.setText("<html><center>"
                                    +"<b style='font-size:11px;color:#7a5000'>"+a.subject.code+"</b><br>"
                                    +"<span style='font-size:9px;color:#8a6000'>🔬 LAB · Batch "+a.batchNo+"</span><br>"
                                    +"<span style='font-size:9px;color:#a07000'>"+a.teacher.name+rStr+"</span>"
                                    +concInfo
                                    +"</center></html>");
                            lbl.setBackground(LAB_BG);
                            lbl.setBorder(BorderFactory.createCompoundBorder(
                                    BorderFactory.createMatteBorder(3,3,0,3,LAB_BORDER),
                                    new EmptyBorder(2,3,2,3)));
                        } else {
                            String rStr=a.room!=null?" ["+a.room.name+"]":"";
                            lbl.setText("<html><center>"
                                    +"<b>"+a.subject.code+"</b><br>"
                                    +"<span style='font-size:9px;color:#445'>"+a.teacher.name+"</span><br>"
                                    +"<span style='font-size:8px;color:#778'>"+rStr+"</span>"
                                    +"</center></html>");
                            lbl.setBackground(yrBg); lbl.setForeground(yrBd);
                        }
                        return lbl;
                    }
                });

                JScrollPane sp=new JScrollPane(table);
                sp.setBorder(new EmptyBorder(0,12,0,12)); sp.setAlignmentX(LEFT_ALIGNMENT);
                card.add(sp); resultHolder.add(card); resultHolder.add(Box.createVerticalStrut(14));
            }
            resultHolder.add(Box.createVerticalStrut(12));
        }
        resultHolder.revalidate(); resultHolder.repaint();
    }

    // ═══════════════════════════════════════════════════════════
    // EXPORT TO EXCEL
    // ═══════════════════════════════════════════════════════════

    void exportToExcel() {
        if (sections.isEmpty()) { showError("No timetable generated yet."); return; }

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save Timetable as Excel");
        fc.setSelectedFile(new File("Timetable_Gen" + currentGenId + ".xlsx"));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Excel Workbook (*.xlsx)", "xlsx"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File outFile = fc.getSelectedFile();
        if (!outFile.getName().toLowerCase().endsWith(".xlsx"))
            outFile = new File(outFile.getAbsolutePath() + ".xlsx");

        try (XSSFWorkbook wb = new XSSFWorkbook()) {

            // ── Shared styles ─────────────────────────────────────────

            // Helper: create a solid-fill cell style
            java.util.function.BiFunction<short[], Boolean, XSSFCellStyle> mkStyle = (rgb, bold) -> {
                XSSFCellStyle st = wb.createCellStyle();
                XSSFFont font = wb.createFont();
                font.setBold(bold);
                font.setFontHeightInPoints((short) 9);
                st.setFont(font);
                st.setAlignment(HorizontalAlignment.CENTER);
                st.setVerticalAlignment(VerticalAlignment.CENTER);
                st.setWrapText(true);
                st.setBorderTop(BorderStyle.THIN);
                st.setBorderBottom(BorderStyle.THIN);
                st.setBorderLeft(BorderStyle.THIN);
                st.setBorderRight(BorderStyle.THIN);
                if (rgb != null) {
                    XSSFColor color = new XSSFColor(new byte[]{(byte)(int)rgb[0],(byte)(int)rgb[1],(byte)(int)rgb[2]}, null);
                    st.setFillForegroundColor(color);
                    st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                }
                return st;
            };

            // Pre-built styles
            XSSFCellStyle styleHeader    = mkStyle.apply(new short[]{25, 90,190}, true);   // PRIMARY blue header
            XSSFCellStyle styleDay       = mkStyle.apply(new short[]{219,234,254}, true);  // light blue day header
            XSSFCellStyle styleBreak     = mkStyle.apply(new short[]{228,230,238}, false); // grey break
            XSSFCellStyle styleLab       = mkStyle.apply(new short[]{255,241,196}, true);  // yellow lab
            XSSFCellStyle styleLabCont   = mkStyle.apply(new short[]{255,249,225}, false); // pale yellow lab cont
            XSSFCellStyle styleFree      = mkStyle.apply(new short[]{248,248,252}, false); // near-white free
            XSSFCellStyle styleTime      = mkStyle.apply(new short[]{238,242,255}, true);  // light purple time col
            XSSFCellStyle styleTitle     = mkStyle.apply(new short[]{25, 90,190}, true);   // big title row

            // White font for header
            XSSFFont whiteFont = wb.createFont();
            whiteFont.setBold(true); whiteFont.setFontHeightInPoints((short)11); whiteFont.setColor(IndexedColors.WHITE.getIndex());
            styleHeader.setFont(whiteFont);
            styleTitle.setFont(whiteFont);

            // Year-coloured lecture styles
            short[][][] yearColors = {
                    {{219,234,254},{37, 99,235}},
                    {{209,250,229},{16,185,129}},
                    {{254,243,199},{217,119,6 }},
                    {{252,231,230},{220, 38, 38}}
            };
            XSSFCellStyle[] styleYear = new XSSFCellStyle[4];
            for (int y=0; y<4; y++) {
                styleYear[y] = mkStyle.apply(yearColors[y][0], false);
            }

            // ── One sheet per section ─────────────────────────────────

            for (int secIdx = 0; secIdx < sections.size(); secIdx++) {
                Section sec = sections.get(secIdx);
                String sheetName = sec.shortLabel();
                XSSFSheet sheet = wb.createSheet(sheetName);
                sheet.setDefaultColumnWidth(18);
                sheet.setDefaultRowHeightInPoints(36);

                int rowNum = 0;

                // ── Title row ──────────────────────────────────────────
                XSSFRow titleRow = sheet.createRow(rowNum++);
                titleRow.setHeightInPoints(28);
                XSSFCell titleCell = titleRow.createCell(0);
                titleCell.setCellValue("Department Master Schedule Blueprint  ·  " + sec.label() + "  |  Gen #" + currentGenId);
                titleCell.setCellStyle(styleTitle);
                sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, DAYS.length)); // col 0..5

                // ── Day-header row ─────────────────────────────────────
                XSSFRow dayRow = sheet.createRow(rowNum++);
                dayRow.setHeightInPoints(20);
                XSSFCell timeHdr = dayRow.createCell(0);
                timeHdr.setCellValue("Time Slot");
                timeHdr.setCellStyle(styleHeader);
                sheet.setColumnWidth(0, 5000); // ~18 chars wide
                for (int d = 0; d < DAYS.length; d++) {
                    XSSFCell dc = dayRow.createCell(d + 1);
                    dc.setCellValue(DAYS[d]);
                    dc.setCellStyle(styleDay);
                }

                // ── Slot rows ──────────────────────────────────────────
                int yearStyleIdx = (sec.year - 1) % 4;

                for (int sl = 0; sl < numSlots; sl++) {
                    TimeSlot ts = timeSlots.get(sl);
                    XSSFRow row = sheet.createRow(rowNum++);
                    row.setHeightInPoints(ts.isBreak ? 16 : 52);

                    // Time label cell
                    XSSFCell timeCell = row.createCell(0);
                    timeCell.setCellValue(ts.label);
                    timeCell.setCellStyle(ts.isBreak ? styleBreak : styleTime);

                    for (int d = 0; d < DAYS.length; d++) {
                        XSSFCell cell = row.createCell(d + 1);

                        if (ts.isBreak) {
                            cell.setCellValue("— Break —");
                            cell.setCellStyle(styleBreak);
                            continue;
                        }

                        Assignment a = grid[secIdx][d][sl];

                        // Check for a concurrent lab overlay in this slot
                        ConcurrentLab conc = null;
                        for (ConcurrentLab cl : concurrentLabs) {
                            if (cl.secIdx == secIdx && cl.day == d && (cl.sl1 == sl || cl.sl2 == sl)) {
                                conc = cl; break;
                            }
                        }

                        if (a == null && conc == null) {
                            cell.setCellValue("○ Free");
                            cell.setCellStyle(styleFree);
                        } else if (a != null && a.labContinued) {
                            String concInfo = "";
                            if (conc != null) concInfo = "\n[B"+conc.batchNo+"] "+conc.subject.code+" "+conc.room.name;
                            cell.setCellValue("↑ LAB cont. B" + a.batchNo + concInfo);
                            cell.setCellStyle(styleLabCont);
                        } else if (a != null && a.isLab) {
                            String concInfo = "";
                            if (conc != null) concInfo = "\n[B"+conc.batchNo+"] "+conc.subject.code
                                    +(conc.teacher!=null?" "+conc.teacher.name:"")
                                    +(conc.room!=null?" "+conc.room.name:"");
                            cell.setCellValue(a.subject.code + " (LAB·B" + a.batchNo + ")"
                                    + "\n" + a.teacher.name
                                    + (a.room != null ? "\n" + a.room.name : "")
                                    + concInfo);
                            cell.setCellStyle(styleLab);
                        } else if (a != null) {
                            cell.setCellValue(a.subject.code
                                    + "\n" + a.teacher.name
                                    + (a.room != null ? "\n[" + a.room.name + "]" : ""));
                            cell.setCellStyle(styleYear[yearStyleIdx]);
                        } else {
                            // concurrent lab on a free primary slot (other batch in same room/time)
                            cell.setCellValue("[B"+conc.batchNo+"] "+conc.subject.code
                                    +"\n"+conc.teacher.name
                                    +(conc.room!=null?"\n"+conc.room.name:"")+" (LAB)");
                            cell.setCellStyle(styleLab);
                        }
                    }
                }

                // ── Legend rows ────────────────────────────────────────
                rowNum++; // blank gap
                XSSFRow legendHdr = sheet.createRow(rowNum++);
                legendHdr.setHeightInPoints(16);
                XSSFCell lh = legendHdr.createCell(0);
                lh.setCellValue("Legend");
                lh.setCellStyle(styleHeader);
                sheet.addMergedRegion(new CellRangeAddress(rowNum-1, rowNum-1, 0, 2));

                String[][] legendItems = {
                        {"Lecture",     "Year-coloured cell — Subject code, Teacher, Room"},
                        {"Lab START",   "Yellow — Subject LAB·Batch, Teacher, Room"},
                        {"Lab cont.",   "Pale yellow — continuation of 2-slot lab block"},
                        {"Free period", "Near-white — no class scheduled"},
                        {"Break",       "Grey — Recess / Lunch break"},
                        {"[Bx] ...",    "Concurrent batch running in parallel lab room"}
                };
                for (String[] item : legendItems) {
                    XSSFRow lr = sheet.createRow(rowNum++);
                    lr.setHeightInPoints(14);
                    lr.createCell(0).setCellValue(item[0]);
                    lr.createCell(1).setCellValue(item[1]);
                }

                // Auto-size day columns
                for (int d = 0; d <= DAYS.length; d++) sheet.autoSizeColumn(d);
                // Keep time column a fixed width
                sheet.setColumnWidth(0, 4800);
            }

            // ── Summary sheet ─────────────────────────────────────────
            XSSFSheet summary = wb.createSheet("Summary");
            summary.setDefaultColumnWidth(20);
            int r = 0;

            XSSFRow sh0 = summary.createRow(r++);
            XSSFCell sc0 = sh0.createCell(0);
            sc0.setCellValue("Universal Departmental Timetable — Generation #" + currentGenId);
            sc0.setCellStyle(styleTitle);
            summary.addMergedRegion(new CellRangeAddress(0,0,0,4));

            r++; // gap
            String[] sumHdr = {"Section","Year","Division","Subjects","Days"};
            XSSFRow shdr = summary.createRow(r++);
            for (int c=0;c<sumHdr.length;c++) {
                XSSFCell hc = shdr.createCell(c);
                hc.setCellValue(sumHdr[c]);
                hc.setCellStyle(styleHeader);
            }

            for (int secIdx=0;secIdx<sections.size();secIdx++) {
                Section sec = sections.get(secIdx);
                XSSFRow sr = summary.createRow(r++);
                sr.createCell(0).setCellValue(sec.label());
                sr.createCell(1).setCellValue(sec.year);
                sr.createCell(2).setCellValue(String.valueOf(sec.div));
                // Count distinct subjects for this section
                java.util.Set<String> usedSubjects = new java.util.LinkedHashSet<>();
                for (int d=0;d<DAYS.length;d++)
                    for (int sl=0;sl<numSlots;sl++) {
                        Assignment a = grid[secIdx][d][sl];
                        if (a!=null && a.subject!=null && !a.labContinued)
                            usedSubjects.add(a.subject.code);
                    }
                sr.createCell(3).setCellValue(String.join(", ", usedSubjects));
                sr.createCell(4).setCellValue(String.join(", ", DAYS));
            }
            for (int c=0;c<5;c++) summary.autoSizeColumn(c);

            // ── Write file ────────────────────────────────────────────
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                wb.write(fos);
            }

            JOptionPane.showMessageDialog(this,
                    "Excel file saved:\n" + outFile.getAbsolutePath(),
                    "Export Successful", JOptionPane.INFORMATION_MESSAGE);

        } catch (IOException ex) {
            showError("Excel export failed: " + ex.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // PRINT / PDF
    // ═══════════════════════════════════════════════════════════

    void printToPdf() {
        if (sections.isEmpty()){showError("No timetable generated yet.");return;}
        PrinterJob pj=PrinterJob.getPrinterJob();
        PageFormat pf=pj.defaultPage();
        Paper paper=new Paper();
        double pw=841.89,ph=595.28,mg=26;
        paper.setSize(pw,ph); paper.setImageableArea(mg,mg,pw-mg*2,ph-mg*2);
        pf.setPaper(paper); pf.setOrientation(PageFormat.LANDSCAPE);

        final int totalPages=sections.size();

        pj.setPrintable((g,pageFormat,pageIndex)->{
            if (pageIndex>=totalPages) return Printable.NO_SUCH_PAGE;
            Graphics2D g2=(Graphics2D)g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            double ix=pageFormat.getImageableX(),iy=pageFormat.getImageableY();
            double iw=pageFormat.getImageableWidth();
            g2.translate(ix,iy);

            Section sec=sections.get(pageIndex);
            Color yrBd=YEAR_BD[(sec.year-1)%YEAR_BD.length];
            Color yrBg=YEAR_BG[(sec.year-1)%YEAR_BG.length];
            int y=0;

            g2.setColor(PRIMARY); g2.fillRect(0,y,(int)iw,28);
            g2.setColor(Color.WHITE); g2.setFont(new Font("Dialog",Font.BOLD,13));
            g2.drawString("Department  ·  "+sec.label()
                    +"  |  Gen #"+currentGenId
                    +"  |  Page "+(pageIndex+1)+" of "+totalPages, 10, y+19);
            y+=34;

            drawLegendItem(g2,10,y,new Color(210,230,255),new Color(30,80,200),"Lecture");
            drawLegendItem(g2,100,y,LAB_BG,LAB_BORDER,"Lab Start");
            drawLegendItem(g2,185,y,LAB_CONT,LAB_BORDER,"Lab cont.");
            drawLegendItem(g2,265,y,FREE_BG,FREE_FG,"Free");
            drawLegendItem(g2,320,y,BREAK_BG,BREAK_FG,"Break"); y+=18;

            int timeColW=82,dayColW=(int)((iw-timeColW)/DAYS.length);
            int rowH=44,breakH=18,headerH=22;

            g2.setColor(new Color(238,242,255)); g2.fillRect(0,y,(int)iw,headerH);
            g2.setColor(new Color(200,208,240)); g2.drawRect(0,y,(int)iw-1,headerH-1);
            g2.setFont(new Font("Dialog",Font.BOLD,10)); g2.setColor(TEXT_DARK);
            g2.drawString("Time Slot",4,y+15);
            for (int d=0;d<DAYS.length;d++) {
                int cx=timeColW+d*dayColW; String day=DAYS[d];
                g2.drawString(day,cx+dayColW/2-g2.getFontMetrics().stringWidth(day)/2,y+15);
            }
            y+=headerH;

            for (int sl=0;sl<numSlots;sl++) {
                TimeSlot ts=timeSlots.get(sl);
                int rh=ts.isBreak?breakH:rowH;
                if (ts.isBreak) {
                    g2.setColor(BREAK_BG); g2.fillRect(0,y,(int)iw,rh);
                    g2.setColor(BREAK_FG); g2.setFont(new Font("Dialog",Font.ITALIC,8));
                    g2.drawString("☕ "+ts.label,4,y+rh-4);
                } else {
                    g2.setColor(new Color(246,248,255)); g2.fillRect(0,y,timeColW,rh);
                    g2.setColor(TEXT_MUTED); g2.setFont(new Font("Dialog",Font.BOLD,8));
                    drawCenteredStr(g2,ts.label,0,y,timeColW,rh);
                }
                for (int d=0;d<DAYS.length;d++) {
                    int cx=timeColW+d*dayColW;
                    if (ts.isBreak) {
                        g2.setColor(BREAK_BG); g2.fillRect(cx,y,dayColW,rh);
                        g2.setColor(BREAK_FG); g2.setFont(new Font("Dialog",Font.ITALIC,8));
                        String bt="— Break —";
                        g2.drawString(bt,cx+dayColW/2-g2.getFontMetrics().stringWidth(bt)/2,y+rh-4);
                    } else {
                        Assignment a=grid[pageIndex][d][sl];
                        if (a==null){
                            g2.setColor(FREE_BG); g2.fillRect(cx,y,dayColW,rh);
                            g2.setColor(FREE_FG); g2.setFont(new Font("Dialog",Font.ITALIC,8));
                            drawCenteredStr(g2,"○ Free",cx,y,dayColW,rh);
                        } else if (a.labContinued){
                            g2.setColor(LAB_CONT); g2.fillRect(cx,y,dayColW,rh);
                            g2.setColor(LAB_BORDER); g2.drawRect(cx,y,dayColW-1,rh-1);
                            g2.setFont(new Font("Dialog",Font.ITALIC,8)); g2.setColor(new Color(130,85,0));
                            drawCenteredStr(g2,"↑ LAB cont. B"+a.batchNo,cx,y,dayColW,rh);
                        } else if (a.isLab){
                            g2.setColor(LAB_BG); g2.fillRect(cx,y,dayColW,rh);
                            g2.setColor(LAB_BORDER); g2.drawRect(cx,y,dayColW-1,rh-1);
                            g2.setFont(new Font("Dialog",Font.BOLD,9)); g2.setColor(new Color(100,60,0));
                            int ty=y+13;
                            g2.drawString(a.subject.code,cx+dayColW/2-g2.getFontMetrics().stringWidth(a.subject.code)/2,ty);
                            g2.setFont(new Font("Dialog",Font.PLAIN,8)); g2.setColor(new Color(140,90,0));
                            String bs="LAB·B"+a.batchNo+(a.room!=null?" "+a.room.name:"");
                            g2.drawString(bs,cx+dayColW/2-g2.getFontMetrics().stringWidth(bs)/2,ty+11);
                            String tn=trunc(a.teacher.name,13);
                            g2.drawString(tn,cx+dayColW/2-g2.getFontMetrics().stringWidth(tn)/2,ty+22);
                        } else {
                            g2.setColor(yrBg); g2.fillRect(cx,y,dayColW,rh);
                            g2.setColor(yrBd); g2.drawRect(cx,y,dayColW-1,rh-1);
                            g2.setFont(new Font("Dialog",Font.BOLD,9)); g2.setColor(yrBd.darker());
                            int ty=y+12;
                            g2.drawString(a.subject.code,cx+dayColW/2-g2.getFontMetrics().stringWidth(a.subject.code)/2,ty);
                            g2.setFont(new Font("Dialog",Font.PLAIN,8)); g2.setColor(new Color(55,60,90));
                            String tn=trunc(a.teacher.name,13);
                            g2.drawString(tn,cx+dayColW/2-g2.getFontMetrics().stringWidth(tn)/2,ty+12);
                            if (a.room!=null){
                                String rn="["+a.room.name+"]";
                                g2.drawString(rn,cx+dayColW/2-g2.getFontMetrics().stringWidth(rn)/2,ty+22);
                            }
                        }
                    }
                    g2.setColor(new Color(210,215,240));
                    g2.drawRect(timeColW+d*dayColW,y,dayColW-1,rh-1);
                }
                g2.setColor(new Color(210,215,240));
                g2.drawRect(0,y,timeColW-1,rh-1);
                y+=rh;
            }
            g2.setFont(new Font("Dialog",Font.ITALIC,8)); g2.setColor(TEXT_MUTED);
            g2.drawString("Universal Departmental Timetable ·  "+sec.label()
                    +"  ·  Gen #"+currentGenId
                    +"  ·  Page "+(pageIndex+1)+" of "+totalPages, 0, y+14);
            return Printable.PAGE_EXISTS;
        },pf);

        if (pj.printDialog()) {
            try {
                pj.print();
                JOptionPane.showMessageDialog(this,
                        "Sent to printer.\n\nTo save as PDF: choose 'Save as PDF' or 'Microsoft Print to PDF'.",
                        "Print",JOptionPane.INFORMATION_MESSAGE);
            } catch (PrinterException ex){showError("Print failed: "+ex.getMessage());}
        }
    }

    private void drawLegendItem(Graphics2D g2,int x,int y,Color bg,Color bd,String text){
        g2.setColor(bg); g2.fillRect(x,y,12,12);
        g2.setColor(bd); g2.drawRect(x,y,12,12);
        g2.setColor(TEXT_DARK); g2.setFont(new Font("Dialog",Font.PLAIN,8));
        g2.drawString(text,x+16,y+9);
    }
    private void drawCenteredStr(Graphics2D g2,String s,int x,int y,int w,int h){
        FontMetrics fm=g2.getFontMetrics();
        g2.drawString(s,x+(w-fm.stringWidth(s))/2,y+(h+fm.getAscent())/2-2);
    }
    private String trunc(String s,int max){return s.length()>max?s.substring(0,max-1)+"…":s;}

    // ═══════════════════════════════════════════════════════════
    // WRAP LAYOUT
    // ═══════════════════════════════════════════════════════════

    static class WrapLayout extends FlowLayout {
        WrapLayout(int a,int h,int v){super(a,h,v);}
        public Dimension preferredLayoutSize(Container t){return ls(t,true);}
        public Dimension minimumLayoutSize(Container t){return ls(t,false);}
        private Dimension ls(Container target,boolean pref){
            synchronized(target.getTreeLock()){
                int tw=target.getSize().width; if(tw==0) tw=Integer.MAX_VALUE;
                int hg=getHgap(),vg=getVgap(); Insets ins=target.getInsets();
                int mw=tw-ins.left-ins.right-hg*2,w=0,h=0,rw=0,rh=0;
                for(Component m:target.getComponents()){
                    if(!m.isVisible()) continue;
                    Dimension d=pref?m.getPreferredSize():m.getMinimumSize();
                    if(rw+d.width>mw){w=Math.max(w,rw);h+=rh+vg;rw=d.width;rh=d.height;}
                    else{if(rw>0) rw+=hg;rw+=d.width;rh=Math.max(rh,d.height);}
                }
                w=Math.max(w,rw)+ins.left+ins.right+hg*2;
                h+=rh+ins.top+ins.bottom+vg*2;
                return new Dimension(w,h);
            }
        }
    }


    // UI HELPERS
    // ═══════════════════════════════════════════════════════════

    JPanel roundCard(int w,int h){
        JPanel p=new JPanel(); p.setBackground(CARD_BG);
        p.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(218,224,248),1,true),
                new EmptyBorder(24,28,24,28)));
        if(w>0) p.setPreferredSize(new Dimension(w,h)); return p;
    }
    JLabel styledLabel(String t,int s,int st,Color c){JLabel l=new JLabel(t);l.setFont(new Font("Dialog",st,s));l.setForeground(c);return l;}
    JLabel centered(JLabel l){l.setAlignmentX(CENTER_ALIGNMENT);return l;}
    JButton primaryButton(String t,int w,int h){
        JButton b=new JButton(t);
        b.setFont(new Font("Dialog",Font.BOLD,14)); b.setBackground(PRIMARY); b.setForeground(Color.WHITE);
        b.setBorderPainted(false); b.setFocusPainted(false);
        b.setPreferredSize(new Dimension(w,h)); b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter(){
            public void mouseEntered(MouseEvent e){b.setBackground(PRIMARY2);}
            public void mouseExited(MouseEvent e){b.setBackground(PRIMARY);}
        }); return b;
    }
    JButton outlineButton(String t,int w,int h){
        JButton b=new JButton(t);
        b.setFont(new Font("Dialog",Font.PLAIN,13)); b.setForeground(PRIMARY); b.setBackground(CARD_BG);
        b.setBorder(new LineBorder(PRIMARY,1,true)); b.setFocusPainted(false);
        b.setPreferredSize(new Dimension(w,h)); b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }
    JTextField styledField(String t){
        JTextField f=new JTextField(t); f.setFont(new Font("Dialog",Font.PLAIN,13));
        f.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(208,215,242),1,true),
                new EmptyBorder(5,10,5,10)));
        f.setPreferredSize(new Dimension(0,34)); return f;
    }
    void styleSpinner(JSpinner sp){sp.setFont(new Font("Dialog",Font.PLAIN,13));sp.setPreferredSize(new Dimension(78,34));}
    JLabel sectionHeader(String t){
        JLabel l=new JLabel(t); l.setFont(new Font("Dialog",Font.BOLD,13)); l.setForeground(PRIMARY);
        l.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0,3,0,0,PRIMARY),
                new EmptyBorder(2,8,2,0)));
        l.setAlignmentX(LEFT_ALIGNMENT); return l;
    }
    JLabel sectionHeader2(String t){
        JLabel l=new JLabel(t); l.setFont(new Font("Dialog",Font.BOLD,18)); l.setForeground(TEXT_DARK);
        l.setAlignmentX(LEFT_ALIGNMENT); return l;
    }
    JLabel colHeader(String t){JLabel l=styledLabel(t,11,Font.BOLD,TEXT_MUTED);l.setAlignmentX(LEFT_ALIGNMENT);return l;}
    JPanel hRow(String label,JComponent right){
        JPanel row=new JPanel(new BorderLayout(12,0)); row.setBackground(CARD_BG);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE,46)); row.setAlignmentX(LEFT_ALIGNMENT);
        row.add(styledLabel(label,13,Font.PLAIN,TEXT_DARK),BorderLayout.CENTER);
        if(right!=null) row.add(right,BorderLayout.EAST); return row;
    }
    JPanel navRow(){JPanel p=new JPanel(new FlowLayout(FlowLayout.RIGHT,0,0));p.setBackground(BG);p.setAlignmentX(LEFT_ALIGNMENT);return p;}
    void showError(String m){JOptionPane.showMessageDialog(this,m,"Input Error",JOptionPane.ERROR_MESSAGE);}

    private ImageIcon generateQRCodeIcon(String text, int width, int height) {
        try {
            java.util.Map<EncodeHintType, Object> hints = new java.util.HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, width, height, hints);

            BufferedImage img = MatrixToImageWriter.toBufferedImage(bitMatrix);
            return new ImageIcon(img);
        } catch (Exception e) {
            System.err.println("QR generation skipped: " + e.getMessage());
            return null;
        }
    }

    private int calculateFacultySatisfaction() {
        int totalChecks = 0;
        int penalties = 0;

        // Loop through each teacher to evaluate their schedule workload comfort
        for (Teacher t : teachers) {
            for (int dy = 0; dy < 5; dy++) { // Assuming 5 days a week (Mon-Fri)
                int dailyLectures = 0;
                int firstActiveSlot = -1;
                int lastActiveSlot = -1;

                for (int sl = 0; sl < numSlots; sl++) {
                    boolean isBusy = false;

                    // Scan all sections to see if this specific teacher is taking a class in this slot
                    for (int secIdx = 0; secIdx < sections.size(); secIdx++) {
                        Assignment a = grid[secIdx][dy][sl];
                        if (a != null && a.teacher != null && a.teacher.equals(t))  {
                            isBusy = true;
                            break;
                        }
                    }

                    if (isBusy) {
                        dailyLectures++;
                        if (firstActiveSlot == -1) firstActiveSlot = sl;
                        lastActiveSlot = sl;
                    }
                }

                // Only evaluate metrics on days the teacher actually has classes scheduled
                if (dailyLectures > 0) {
                    totalChecks++;

                    // Penalty 1: Overload (More than 3 lectures a day is exhausting)
                    if (dailyLectures > 3) {
                        penalties++;
                    }

                    // Penalty 2: Large Gaps (Waiting around empty for more than 2 slots is annoying)
                    int totalSpanningSlots = (lastActiveSlot - firstActiveSlot) + 1;
                    int gapSlots = totalSpanningSlots - dailyLectures;
                    if (gapSlots > 2) {
                        penalties++;
                    }
                }
            }
        }

        if (totalChecks == 0) return 100; // Perfect score default if no classes are allocated yet

        // Convert calculations into a clean percentage score
        int score = 100 - ((penalties * 100) / totalChecks);
        return Math.max(50, Math.min(100, score)); // Keeps the score bound between 50% and 100%
    }

    private void exportTimetableToExcel() {
        try {
            // 1. Open a system save dialog explicitly targeting Excel files
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("Export Blueprint to Excel Spreadsheet");
            fileChooser.setSelectedFile(new File("CSBS_Timetable_Blueprint_" + currentGenId + ".xlsx"));

            int userSelection = fileChooser.showSaveDialog(this);
            if (userSelection != JFileChooser.APPROVE_OPTION) {
                return;
            }

            File fileToSave = fileChooser.getSelectedFile();

            // 2. Open an direct output stream to compose the spreadsheet binary structure
            try (FileWriter writer = new FileWriter(fileToSave)) {
                // Write standard text data separation headers that Excel naturally parses as grids on load
                StringBuilder header = new StringBuilder("Batch / Section\tDay");
                for (int sl = 0; sl < numSlots; sl++) {
                    header.append("\tSlot ").append(sl + 1);
                }
                writer.write(header.toString() + "\n");

                String[] daysText = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday"};

                for (int secIdx = 0; secIdx < sections.size(); secIdx++) {
                    String sectionName = sections.get(secIdx).toString();

                    for (int dy = 0; dy < 5; dy++) {
                        StringBuilder row = new StringBuilder();

                        // Maintain clean indentation layouts for the sections
                        if (dy == 0) {
                            row.append(sectionName).append("\t");
                        } else {
                            row.append("\t");
                        }

                        row.append(daysText[dy]);

                        // Inject active scheduling payload details into cell indices
                        for (int sl = 0; sl < numSlots; sl++) {
                            Assignment a = grid[secIdx][dy][sl];
                            if (a != null && a.subject != null) {
                                String teacherName = (a.teacher != null) ? a.teacher.name : "N/A";
                                row.append("\t").append(a.subject.code).append(" (").append(teacherName).append(")");
                            } else {
                                row.append("\t-");
                            }
                        }
                        writer.write(row.toString() + "\n");
                    }
                    writer.write("\n"); // Generates neat spacing between separate batches
                }
            }

            // If the user forgot to name it .xlsx or named it .xls, automatically fix the file mapping
            String path = fileToSave.getAbsolutePath();
            if (!path.toLowerCase().endsWith(".xlsx") && !path.toLowerCase().endsWith(".xls")) {
                File renamedFile = new File(path + ".xlsx");
                fileToSave.renameTo(renamedFile);
            }

            JOptionPane.showMessageDialog(this, "📊 Excel Timetable Blueprint saved successfully!", "Export Complete", JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error exporting file: " + e.getMessage(), "Export Failure", JOptionPane.ERROR_MESSAGE);
            System.err.println("Excel composition failure: " + e.getMessage());
        }
    }
    // ═══════════════════════════════════════════════════════════
    // MAIN
    // ═══════════════════════════════════════════════════════════

    public static void main(String[] args){
        try{UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());}catch(Exception ignored){}
        SwingUtilities.invokeLater(TimetableGenerator::new);
    }
}