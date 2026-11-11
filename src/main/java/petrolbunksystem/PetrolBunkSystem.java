package petrolbunksystem;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// ==========================================
// 1. DATABASE CONFIGURATION
// ==========================================
class DatabaseConfig {
    static final String URL = System.getenv().getOrDefault(
            "DB_URL", "jdbc:mysql://localhost:3306/petrol_bunk"
    );
    static final String USER = System.getenv().getOrDefault("DB_USER", "root");
    // Keep credentials outside source code. Set DB_PASSWORD before running.
    static final String PASS = System.getenv().getOrDefault("DB_PASSWORD", "");
}

class DatabaseService {
    /**
     * Connection status shown in the dashboard header.
     * null = no database call made yet, TRUE = last call succeeded,
     * FALSE = last call failed (the app keeps running in demo mode).
     */
    static volatile Boolean online = null;

    public static synchronized void saveTransaction(Transaction t, String fuelType) {
        String query = "INSERT INTO transactions (transaction_id, fuel_type, volume, total_cost) VALUES (?, ?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(DatabaseConfig.URL, DatabaseConfig.USER, DatabaseConfig.PASS);
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, t.transactionId);
            pstmt.setString(2, fuelType);
            pstmt.setDouble(3, t.volumeDispensed);
            pstmt.setDouble(4, t.totalCost);
            pstmt.executeUpdate();
            online = true;
        } catch (Exception e) { online = false; }
    }

    public static synchronized void updateTank(String fuelType, double currentVolume) {
        String query = "UPDATE tanks SET current_volume = ? WHERE fuel_type = ?";
        try (Connection conn = DriverManager.getConnection(DatabaseConfig.URL, DatabaseConfig.USER, DatabaseConfig.PASS);
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setDouble(1, currentVolume);
            pstmt.setString(2, fuelType);
            pstmt.executeUpdate();
            online = true;
        } catch (Exception e) { online = false; }
    }

    public static synchronized void saveSettlement(String cashierName, double totalCollections, double dailyExpenses, double netProfit) {
        String query = "INSERT INTO shift_settlements (cashier_name, total_collections, daily_expenses, net_profit) VALUES (?, ?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(DatabaseConfig.URL, DatabaseConfig.USER, DatabaseConfig.PASS);
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, cashierName);
            pstmt.setDouble(2, totalCollections);
            pstmt.setDouble(3, dailyExpenses);
            pstmt.setDouble(4, netProfit);
            pstmt.executeUpdate();
            online = true;
        } catch (Exception e) { online = false; }
    }
}

// ==========================================
// 2. CORE SYSTEM LOGIC (THREADS & MODELS)
// ==========================================
interface DashboardListener {
    void onTransactionComplete(Transaction t, String pumpName, String fuelType, double currentVolume);
    void onShiftEnded(double totalCash, double netProfit);
}

class Tank {
    private String fuelType;
    private double currentVolume;
    private double maxCapacity;

    public Tank(String fuelType, double maxCapacity) {
        this.fuelType = fuelType;
        this.maxCapacity = maxCapacity;
        this.currentVolume = maxCapacity;
    }

    public synchronized boolean deductFuel(double amount) {
        if (currentVolume >= amount) {
            currentVolume -= amount;
            DatabaseService.updateTank(fuelType, currentVolume);
            return true;
        }
        return false;
    }
    public synchronized double getCurrentVolume() { return currentVolume; }
    public String getFuelType() { return fuelType; }
    public double getMaxCapacity() { return maxCapacity; }
}

class Transaction {
    String transactionId;
    double volumeDispensed;
    double totalCost;
    String timestampStr;

    public Transaction(double volume, double cost, String paymentMethod) {
        this.transactionId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.volumeDispensed = volume;
        this.totalCost = cost;
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("HH:mm:ss");
        this.timestampStr = dtf.format(LocalDateTime.now());
    }
}

class Nozzle implements Runnable {
    private String nozzleName;
    private Tank sourceTank;
    private double pricePerLiter;
    private DashboardListener listener;
    // The same Nozzle object is submitted several times to a thread pool,
    // so the list must be thread-safe.
    private List<Transaction> shiftTransactions = Collections.synchronizedList(new ArrayList<>());

    public Nozzle(String nozzleName, Tank sourceTank, double pricePerLiter, DashboardListener listener) {
        this.nozzleName = nozzleName;
        this.sourceTank = sourceTank;
        this.pricePerLiter = pricePerLiter;
        this.listener = listener;
    }

    @Override
    public void run() {
        try {
            Thread.sleep((long) (Math.random() * 2000 + 500));
            double requestedVolume = Math.random() * 15 + 2;

            if (sourceTank.deductFuel(requestedVolume)) {
                double cost = requestedVolume * pricePerLiter;
                Transaction t = new Transaction(requestedVolume, cost, "CASH");
                shiftTransactions.add(t);
                DatabaseService.saveTransaction(t, sourceTank.getFuelType());

                if (listener != null) {
                    listener.onTransactionComplete(t, nozzleName, sourceTank.getFuelType(), sourceTank.getCurrentVolume());
                }
            }
        } catch (Exception e) {}
    }
    public List<Transaction> getTransactions() { return shiftTransactions; }
}

// ==========================================
// 3. PROFESSIONAL DASHBOARD GUI
// ==========================================
public class PetrolBunkSystem extends JFrame implements DashboardListener {

    // ---------- Palette ----------
    static final Color BG        = new Color(0x0B1220);
    static final Color SURFACE   = new Color(0x111B30);
    static final Color ZEBRA     = new Color(0x0F1829);
    static final Color HEADER_BG = new Color(0x0D1628);
    static final Color INPUT     = new Color(0x0A1020);
    static final Color LINE      = new Color(0x22304D);
    static final Color TEXT      = new Color(0xE8EEF9);
    static final Color MUTED     = new Color(0x8A9BB8);
    static final Color ACCENT    = new Color(0x22D3EE);
    static final Color PETROL    = new Color(0xFB923C);
    static final Color DIESEL    = new Color(0x34D399);
    static final Color PROFIT    = new Color(0x4ADE80);
    static final Color DANGER    = new Color(0xF87171);
    static final Color AMBER     = new Color(0xFBBF24);
    static final Color SELECT    = new Color(0x16345A);

    static final String RS = "₹";           // rupee sign
    static final int TANK_CAPACITY = 10000;      // litres per tank
    static final int TOTAL_TXNS = 20;            // 4 pumps x 5 jobs each
    static final String[] PUMPS = {"Pump P1", "Pump P2", "Pump D1", "Pump D2"};

    static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy", Locale.ENGLISH);

    static Font ui(int style, int size)   { return new Font("Segoe UI", style, size); }
    static Font mono(int style, int size) { return new Font("Consolas", style, size); }
    static String money(double v)         { return String.format(Locale.US, RS + " %,.2f", v); }
    static Color fuelColor(String fuel)   { return "Petrol".equals(fuel) ? PETROL : DIESEL; }

    // ---------- State ----------
    private JTextField txtName, txtRole, txtPetrolRate, txtDieselRate, txtExpenses;
    private PillButton btnStart;
    private JTable salesTable;
    private DefaultTableModel tableModel;
    private TankGauge petrolGauge, dieselGauge;
    private BarChart pumpChart;
    private KpiCard kpiRevenue, kpiLitres, kpiTxns, kpiNet;
    private ThinProgress progress;
    private JLabel lblProgress, lblStatus, lblFeedCount, lblTime, lblDate;
    private StatusPill runPill, dbPill;

    private Tank petrolTank, dieselTank;
    private double currentPetrolRate, currentDieselRate, dailyExpenses;
    private String operatorName = "Operator";
    private double revenue, litres;
    private int txnCount;

    public PetrolBunkSystem() {
        setTitle("Automated Fuel Management System");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(Math.min(1280, (int) (scr.width * 0.94)), Math.min(800, (int) (scr.height * 0.92)));
        setMinimumSize(new Dimension(Math.min(1100, scr.width), Math.min(660, scr.height)));
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout());

        add(buildHeader(), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(16, 16));
        body.setBackground(BG);
        body.setBorder(new EmptyBorder(16, 16, 16, 16));
        body.add(buildLeftColumn(), BorderLayout.WEST);
        body.add(buildCenter(), BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);

        resetDashboard();
        startClock();
    }

    // =====================================================================
    // Header
    // =====================================================================
    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 0, new Color(0x14223F), getWidth(), 0, BG));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setPaint(new GradientPaint(0, 0, ACCENT, getWidth() * 0.7f, 0,
                        new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 0)));
                g2.fillRect(0, getHeight() - 2, getWidth(), 2);
                g2.dispose();
            }
        };

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 14));
        left.setOpaque(false);
        left.add(new Logo());
        JPanel titles = new JPanel();
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.setOpaque(false);
        JLabel title = new JLabel("Automated Fuel Management System");
        title.setFont(ui(Font.BOLD, 20));
        title.setForeground(TEXT);
        JLabel sub = new JLabel("Multi-threaded pump simulation  |  Java Swing + MySQL");
        sub.setFont(ui(Font.PLAIN, 12));
        sub.setForeground(MUTED);
        titles.add(title);
        titles.add(sub);
        left.add(titles);
        header.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 14));
        right.setOpaque(false);
        runPill = new StatusPill("IDLE", MUTED);
        dbPill = new StatusPill("DATABASE STANDBY", MUTED);
        JPanel clock = new JPanel();
        clock.setLayout(new BoxLayout(clock, BoxLayout.Y_AXIS));
        clock.setOpaque(false);
        lblTime = new JLabel("--:--:--");
        lblTime.setFont(ui(Font.BOLD, 18));
        lblTime.setForeground(TEXT);
        lblTime.setAlignmentX(Component.RIGHT_ALIGNMENT);
        lblDate = new JLabel(" ");
        lblDate.setFont(ui(Font.PLAIN, 12));
        lblDate.setForeground(MUTED);
        lblDate.setAlignmentX(Component.RIGHT_ALIGNMENT);
        clock.add(lblTime);
        clock.add(lblDate);
        right.add(runPill);
        right.add(dbPill);
        right.add(Box.createHorizontalStrut(8));
        right.add(clock);
        right.add(Box.createHorizontalStrut(6));
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private void startClock() {
        Timer t = new Timer(1000, e -> tick());
        t.setInitialDelay(0);
        t.start();
        tick();
    }

    private void tick() {
        LocalDateTime now = LocalDateTime.now();
        lblTime.setText(now.format(TIME_FMT));
        lblDate.setText(now.format(DATE_FMT));
        Boolean ok = DatabaseService.online;
        if (ok == null)    dbPill.set("DATABASE STANDBY", MUTED);
        else if (ok)       dbPill.set("MYSQL CONNECTED", PROFIT);
        else               dbPill.set("MYSQL OFFLINE - DEMO MODE", AMBER);
    }

    // =====================================================================
    // Left column: configuration + info
    // =====================================================================
    private JComponent buildLeftColumn() {
        JPanel col = new JPanel(new BorderLayout(0, 16));
        col.setOpaque(false);
        col.setPreferredSize(new Dimension(340, 0));
        col.add(buildConfigCard(), BorderLayout.NORTH);
        col.add(buildInfoCard(), BorderLayout.CENTER);
        return col;
    }

    private JComponent buildConfigCard() {
        Card card = new Card(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.NORTHWEST;
        int y = 0;

        g.gridy = y++;
        g.insets = new Insets(0, 0, 14, 0);
        card.add(sectionTitle("SHIFT CONFIGURATION"), g);

        txtName = styledField("Aravinthan");
        txtRole = styledField("System Admin");
        g.gridy = y++;
        g.insets = new Insets(0, 0, 12, 0);
        card.add(pair(labeled("Operator", txtName), labeled("Access level", txtRole)), g);

        txtPetrolRate = styledField("102.50");
        txtDieselRate = styledField("94.20");
        g.gridy = y++;
        card.add(pair(labeled("Petrol rate (" + RS + "/L)", txtPetrolRate),
                      labeled("Diesel rate (" + RS + "/L)", txtDieselRate)), g);

        txtExpenses = styledField("250.00");
        g.gridy = y++;
        card.add(labeled("Operating expenses (" + RS + ")", txtExpenses), g);

        btnStart = new PillButton("START SHIFT", ACCENT, BG);
        btnStart.addActionListener(e -> startSimulation());
        g.gridy = y++;
        g.insets = new Insets(8, 0, 16, 0);
        card.add(btnStart, g);

        lblProgress = new JLabel("Shift progress");
        lblProgress.setFont(ui(Font.PLAIN, 12));
        lblProgress.setForeground(MUTED);
        g.gridy = y++;
        g.insets = new Insets(0, 0, 6, 0);
        card.add(lblProgress, g);

        progress = new ThinProgress();
        g.gridy = y++;
        g.insets = new Insets(0, 0, 12, 0);
        card.add(progress, g);

        lblStatus = new JLabel();
        lblStatus.setFont(ui(Font.PLAIN, 12));
        setStatus("Ready. Set the parameters above and start the shift.", MUTED);
        g.gridy = y;
        g.insets = new Insets(0, 0, 0, 0);
        card.add(lblStatus, g);
        return card;
    }

    private JComponent buildInfoCard() {
        Card card = new Card(new BorderLayout(0, 12));
        card.add(sectionTitle("HOW IT WORKS"), BorderLayout.NORTH);
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);
        String[] points = {
            "Four pumps (2 petrol, 2 diesel) run on a fixed pool of 4 threads.",
            "Tanks are synchronized, so two pumps can never over-draw one tank.",
            "Every sale and the final settlement are saved to MySQL through JDBC.",
            "Pumps notify this dashboard through a listener, so the UI updates live."
        };
        String hex = String.format("#%06X", ACCENT.getRGB() & 0xFFFFFF);
        for (String p : points) {
            JLabel l = new JLabel("<html><body style='width:235px'><font color='" + hex + "'>&#9679;</font>&nbsp;&nbsp;" + p + "</body></html>");
            l.setFont(ui(Font.PLAIN, 13));
            l.setForeground(MUTED);
            l.setBorder(new EmptyBorder(0, 0, 10, 0));
            list.add(l);
        }
        card.add(list, BorderLayout.CENTER);
        return card;
    }

    // =====================================================================
    // Center: KPI row, live feed, right column
    // =====================================================================
    private JComponent buildCenter() {
        JPanel center = new JPanel(new BorderLayout(16, 16));
        center.setOpaque(false);

        JPanel kpis = new JPanel(new GridLayout(1, 4, 16, 0));
        kpis.setOpaque(false);
        kpiRevenue = new KpiCard("Total revenue", "gross sales this shift", money(0), ACCENT);
        kpiLitres  = new KpiCard("Fuel dispensed", "litres across all pumps", "0.0 L", PETROL);
        kpiTxns    = new KpiCard("Transactions", "completed sales", "0 / " + TOTAL_TXNS, DIESEL);
        kpiNet     = new KpiCard("Net profit", "after operating expenses", money(0), PROFIT);
        kpis.add(kpiRevenue);
        kpis.add(kpiLitres);
        kpis.add(kpiTxns);
        kpis.add(kpiNet);
        center.add(kpis, BorderLayout.NORTH);

        center.add(buildTableCard(), BorderLayout.CENTER);
        center.add(buildRightColumn(), BorderLayout.EAST);
        return center;
    }

    private JComponent buildTableCard() {
        Card card = new Card(new BorderLayout(0, 12));
        card.setBorder(new EmptyBorder(18, 0, 8, 0));

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(new EmptyBorder(0, 20, 0, 20));
        head.add(sectionTitle("LIVE TRANSACTION FEED"), BorderLayout.WEST);
        lblFeedCount = new JLabel("0 transactions");
        lblFeedCount.setFont(ui(Font.PLAIN, 12));
        lblFeedCount.setForeground(MUTED);
        head.add(lblFeedCount, BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);

        String[] cols = {"TIME", "TXN ID", "PUMP", "FUEL", "LITRES", "AMOUNT"};
        tableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        salesTable = new JTable(tableModel) {
            @Override public void paint(Graphics g) {
                super.paint(g);
                if (getRowCount() == 0) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setFont(ui(Font.PLAIN, 14));
                    g2.setColor(MUTED);
                    String s = "No transactions yet. Press START SHIFT to begin.";
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(s, (getWidth() - fm.stringWidth(s)) / 2, Math.max(60, getHeight() / 2));
                    g2.dispose();
                }
            }
        };
        salesTable.setFont(ui(Font.PLAIN, 13));
        salesTable.setRowHeight(36);
        salesTable.setShowGrid(false);
        salesTable.setIntercellSpacing(new Dimension(0, 0));
        salesTable.setFillsViewportHeight(true);
        salesTable.setBackground(SURFACE);
        salesTable.setForeground(TEXT);
        salesTable.setFocusable(false);
        salesTable.setDefaultRenderer(Object.class, new TxnRenderer());
        int[] widths = {90, 125, 100, 85, 105, 125};
        for (int i = 0; i < widths.length; i++) {
            salesTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        JTableHeader th = salesTable.getTableHeader();
        th.setDefaultRenderer(new HeaderRenderer());
        th.setPreferredSize(new Dimension(0, 38));
        th.setReorderingAllowed(false);
        th.setResizingAllowed(false);

        JScrollPane sp = new JScrollPane(salesTable,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        // Not null: JTable re-installs its default (white) border when the border is null.
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setViewportBorder(null);
        sp.setBackground(SURFACE);
        sp.getViewport().setBackground(SURFACE);
        sp.getVerticalScrollBar().setUI(new SlimScrollBarUI());
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(10, 0));
        sp.getVerticalScrollBar().setBackground(SURFACE);
        JPanel corner = new JPanel();
        corner.setBackground(HEADER_BG);
        sp.setCorner(JScrollPane.UPPER_RIGHT_CORNER, corner);
        card.add(sp, BorderLayout.CENTER);
        return card;
    }

    private JComponent buildRightColumn() {
        JPanel col = new JPanel(new BorderLayout(0, 16));
        col.setOpaque(false);
        col.setPreferredSize(new Dimension(330, 0));

        Card tanks = new Card(new BorderLayout(0, 14));
        tanks.add(sectionTitle("TANK INVENTORY"), BorderLayout.NORTH);
        JPanel gauges = new JPanel(new GridLayout(2, 1, 0, 14));
        gauges.setOpaque(false);
        petrolGauge = new TankGauge("Petrol", TANK_CAPACITY, PETROL);
        dieselGauge = new TankGauge("Diesel", TANK_CAPACITY, DIESEL);
        gauges.add(petrolGauge);
        gauges.add(dieselGauge);
        tanks.add(gauges, BorderLayout.CENTER);

        Card chart = new Card(new BorderLayout(0, 12));
        chart.add(sectionTitle("REVENUE BY PUMP"), BorderLayout.NORTH);
        pumpChart = new BarChart();
        for (String p : PUMPS) pumpChart.addBar(p, p.endsWith("P1") || p.endsWith("P2") ? PETROL : DIESEL);
        chart.add(pumpChart, BorderLayout.CENTER);

        col.add(tanks, BorderLayout.NORTH);
        col.add(chart, BorderLayout.CENTER);
        return col;
    }

    // =====================================================================
    // Simulation control (same logic as the original project)
    // =====================================================================
    private void startSimulation() {
        double pr, dr, ex;
        try {
            pr = Double.parseDouble(txtPetrolRate.getText().trim());
            dr = Double.parseDouble(txtDieselRate.getText().trim());
            ex = Double.parseDouble(txtExpenses.getText().trim());
            if (pr <= 0 || dr <= 0 || ex < 0) throw new NumberFormatException("range");
        } catch (NumberFormatException nfe) {
            showDialog("Invalid input", "Please check the inputs", DANGER, null,
                    "Fuel rates must be numbers greater than zero, and operating expenses must be zero or more.");
            return;
        }
        currentPetrolRate = pr;
        currentDieselRate = dr;
        dailyExpenses = ex;
        operatorName = txtName.getText().trim().isEmpty() ? "Operator" : txtName.getText().trim();

        resetDashboard();
        btnStart.setEnabled(false);
        runPill.set("RUNNING", ACCENT);
        setStatus("Shift in progress: 4 pump threads are dispensing fuel...", ACCENT);

        petrolTank = new Tank("Petrol", TANK_CAPACITY);
        dieselTank = new Tank("Diesel", TANK_CAPACITY);
        final String cashierName = operatorName;
        final double petrolRate = currentPetrolRate;
        final double dieselRate = currentDieselRate;
        final double expenses = dailyExpenses;
        final Tank pTank = petrolTank;
        final Tank dTank = dieselTank;

        Thread controller = new Thread(() -> {
            Nozzle p1 = new Nozzle(PUMPS[0], pTank, petrolRate, this);
            Nozzle p2 = new Nozzle(PUMPS[1], pTank, petrolRate, this);
            Nozzle d1 = new Nozzle(PUMPS[2], dTank, dieselRate, this);
            Nozzle d2 = new Nozzle(PUMPS[3], dTank, dieselRate, this);

            ExecutorService executor = Executors.newFixedThreadPool(4);
            for (int i = 0; i < 5; i++) {
                executor.submit(p1); executor.submit(p2);
                executor.submit(d1); executor.submit(d2);
            }
            executor.shutdown();
            try { executor.awaitTermination(30, TimeUnit.SECONDS); } catch (Exception e) {}

            List<Transaction> all = new ArrayList<>();
            all.addAll(p1.getTransactions()); all.addAll(p2.getTransactions());
            all.addAll(d1.getTransactions()); all.addAll(d2.getTransactions());

            double totalCash = 0;
            for (Transaction t : all) totalCash += t.totalCost;
            double netProfit = totalCash - expenses;

            DatabaseService.saveSettlement(cashierName, totalCash, expenses, netProfit);

            onShiftEnded(totalCash, netProfit);
        }, "shift-controller");
        controller.start();
    }

    private void resetDashboard() {
        revenue = 0;
        litres = 0;
        txnCount = 0;
        tableModel.setRowCount(0);
        kpiRevenue.setValue(money(0));
        kpiLitres.setValue("0.0 L");
        kpiTxns.setValue("0 / " + TOTAL_TXNS);
        kpiNet.setValue(money(0));
        kpiNet.setValueColor(TEXT);
        petrolGauge.reset();
        dieselGauge.reset();
        pumpChart.reset();
        progress.setFraction(0);
        lblProgress.setText("Shift progress   0 / " + TOTAL_TXNS + " transactions");
        lblFeedCount.setText("0 transactions");
    }

    @Override
    public void onTransactionComplete(Transaction t, String pumpName, String fuelType, double currentVolume) {
        SwingUtilities.invokeLater(() -> {
            tableModel.insertRow(0, new Object[]{
                t.timestampStr, t.transactionId, pumpName, fuelType,
                String.format(Locale.US, "%.2f", t.volumeDispensed),
                money(t.totalCost)
            });
            revenue += t.totalCost;
            litres += t.volumeDispensed;
            txnCount++;

            kpiRevenue.setValue(money(revenue));
            kpiLitres.setValue(String.format(Locale.US, "%,.1f L", litres));
            kpiTxns.setValue(txnCount + " / " + TOTAL_TXNS);
            pumpChart.addValue(pumpName, t.totalCost);

            TankGauge gauge = "Petrol".equals(fuelType) ? petrolGauge : dieselGauge;
            if (currentVolume < gauge.getTarget()) gauge.setLevel(currentVolume);

            progress.setFraction(txnCount / (double) TOTAL_TXNS);
            lblProgress.setText("Shift progress   " + txnCount + " / " + TOTAL_TXNS + " transactions");
            lblFeedCount.setText(txnCount + (txnCount == 1 ? " transaction" : " transactions"));
        });
    }

    @Override
    public void onShiftEnded(double totalCash, double netProfit) {
        SwingUtilities.invokeLater(() -> {
            runPill.set("COMPLETE", PROFIT);
            kpiNet.setValue(money(netProfit));
            kpiNet.setValueColor(netProfit >= 0 ? PROFIT : DANGER);
            progress.setFraction(1.0);
            setStatus("Shift complete. All pumps are offline.", PROFIT);
            btnStart.setEnabled(true);
            btnStart.setText("RUN AGAIN");

            Object[][] rows = {
                {"Gross revenue", money(totalCash), TEXT},
                {"Operating expenses", money(dailyExpenses), TEXT},
                {"Net profit", money(netProfit), netProfit >= 0 ? PROFIT : DANGER},
                {"Fuel dispensed", String.format(Locale.US, "%,.1f L", litres), TEXT},
                {"Transactions", String.valueOf(txnCount), TEXT}
            };
            String note = Boolean.TRUE.equals(DatabaseService.online)
                    ? "Settlement for " + operatorName + " was saved to the database."
                    : "Database offline: results are shown in demo mode and were not saved.";
            showDialog("End of day summary", "Shift complete", PROFIT, rows, note);
        });
    }

    private void setStatus(String msg, Color c) {
        lblStatus.setText("<html><body style='width:245px'>" + msg + "</body></html>");
        lblStatus.setForeground(c);
    }

    // =====================================================================
    // Themed dialog (replaces the grey JOptionPane)
    // =====================================================================
    private void showDialog(String title, String heading, Color accent, Object[][] rows, String note) {
        JDialog d = new JDialog(this, title, true);
        JPanel root = new JPanel(new BorderLayout(0, 16));
        root.setBackground(SURFACE);
        root.setBorder(new CompoundBorder(new MatteBorder(3, 0, 0, 0, accent), new EmptyBorder(22, 28, 20, 28)));

        JLabel h = new JLabel(heading);
        h.setFont(ui(Font.BOLD, 20));
        h.setForeground(TEXT);
        root.add(h, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        if (rows != null) {
            for (Object[] r : rows) {
                JPanel line = new JPanel(new BorderLayout());
                line.setOpaque(false);
                line.setAlignmentX(Component.LEFT_ALIGNMENT);
                line.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, LINE), new EmptyBorder(9, 0, 9, 0)));
                JLabel k = new JLabel((String) r[0]);
                k.setFont(ui(Font.PLAIN, 14));
                k.setForeground(MUTED);
                JLabel v = new JLabel((String) r[1]);
                v.setFont(ui(Font.BOLD, 15));
                v.setForeground((Color) r[2]);
                line.add(k, BorderLayout.WEST);
                line.add(v, BorderLayout.EAST);
                body.add(line);
            }
        }
        if (note != null) {
            JLabel n = new JLabel("<html><body style='width:380px'>" + note + "</body></html>");
            n.setFont(ui(Font.PLAIN, 13));
            n.setForeground(MUTED);
            n.setAlignmentX(Component.LEFT_ALIGNMENT);
            n.setBorder(new EmptyBorder(rows != null ? 14 : 0, 0, 0, 0));
            body.add(n);
        }
        root.add(body, BorderLayout.CENTER);

        PillButton ok = new PillButton("CLOSE", accent, BG);
        ok.addActionListener(e -> d.dispose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        south.setOpaque(false);
        south.add(ok);
        root.add(south, BorderLayout.SOUTH);

        d.setContentPane(root);
        d.getRootPane().setDefaultButton(ok);
        d.pack();
        if (d.getWidth() < 460) d.setSize(460, d.getHeight());
        d.setResizable(false);
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    // =====================================================================
    // Small layout helpers
    // =====================================================================
    private static JLabel sectionTitle(String s) {
        JLabel l = new JLabel(s);
        l.setFont(ui(Font.BOLD, 12));
        l.setForeground(MUTED);
        return l;
    }

    private static JPanel labeled(String label, JComponent field) {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setOpaque(false);
        JLabel l = new JLabel(label);
        l.setFont(ui(Font.PLAIN, 12));
        l.setForeground(MUTED);
        p.add(l, BorderLayout.NORTH);
        p.add(field, BorderLayout.CENTER);
        return p;
    }

    private static JPanel pair(JComponent a, JComponent b) {
        JPanel p = new JPanel(new GridLayout(1, 2, 12, 0));
        p.setOpaque(false);
        p.add(a);
        p.add(b);
        return p;
    }

    private static JTextField styledField(String value) {
        JTextField f = new JTextField(value);
        f.setFont(ui(Font.PLAIN, 14));
        f.setForeground(TEXT);
        f.setBackground(INPUT);
        f.setCaretColor(ACCENT);
        f.setSelectionColor(new Color(0x155E75));
        f.setSelectedTextColor(Color.WHITE);
        final CompoundBorder normal = new CompoundBorder(new LineBorder(LINE, 1), new EmptyBorder(8, 10, 8, 10));
        final CompoundBorder focused = new CompoundBorder(new LineBorder(ACCENT, 1), new EmptyBorder(8, 10, 8, 10));
        f.setBorder(normal);
        f.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { f.setBorder(focused); }
            @Override public void focusLost(FocusEvent e)   { f.setBorder(normal); }
        });
        return f;
    }

    // =====================================================================
    // Custom components
    // =====================================================================

    /** Rounded dark card with an optional coloured strip on the left. */
    static class Card extends JPanel {
        private Color strip;
        Card(LayoutManager lm) {
            super(lm);
            setOpaque(false);
            setBorder(new EmptyBorder(18, 20, 18, 20));
        }
        void setStrip(Color c) { strip = c; repaint(); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Shape shape = new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1, getHeight() - 1, 16, 16);
            g2.setColor(SURFACE);
            g2.fill(shape);
            if (strip != null) {
                Shape old = g2.getClip();
                g2.clip(shape);
                g2.setColor(strip);
                g2.fillRect(0, 0, 4, getHeight());
                g2.setClip(old);
            }
            g2.setColor(LINE);
            g2.draw(shape);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Headline number with a caption, used in the KPI row. */
    static class KpiCard extends Card {
        private final JLabel value = new JLabel();
        KpiCard(String caption, String sub, String initial, Color accent) {
            super(new BorderLayout(0, 4));
            setBorder(new EmptyBorder(14, 22, 14, 16));
            setStrip(accent);
            JLabel c = new JLabel(caption.toUpperCase());
            c.setFont(ui(Font.BOLD, 11));
            c.setForeground(MUTED);
            value.setText(initial);
            value.setFont(ui(Font.BOLD, 26));
            value.setForeground(TEXT);
            JLabel s = new JLabel(sub);
            s.setFont(ui(Font.PLAIN, 12));
            s.setForeground(MUTED);
            add(c, BorderLayout.NORTH);
            add(value, BorderLayout.CENTER);
            add(s, BorderLayout.SOUTH);
        }
        void setValue(String s)      { value.setText(s); }
        void setValueColor(Color c)  { value.setForeground(c); }
    }

    /** Rounded button with hover and pressed states. */
    static class PillButton extends JButton {
        private final Color base;
        private boolean hover;
        PillButton(String text, Color base, Color fg) {
            super(text);
            this.base = base;
            setFont(ui(Font.BOLD, 14));
            setForeground(fg);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setBorder(new EmptyBorder(12, 22, 12, 22));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e)  { hover = false; repaint(); }
            });
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c;
            if (!isEnabled())                  c = new Color(0x24324F);
            else if (getModel().isPressed())   c = base.darker();
            else if (hover)                    c = base.brighter();
            else                               c = base;
            g2.setPaint(new GradientPaint(0, 0, c, 0, getHeight(), isEnabled() ? c.darker() : c));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Small rounded status chip with a coloured dot. */
    static class StatusPill extends JComponent {
        private String text;
        private Color color;
        StatusPill(String text, Color color) {
            this.text = text;
            this.color = color;
            setFont(ui(Font.BOLD, 11));
        }
        void set(String text, Color color) {
            if (text.equals(this.text) && color.equals(this.color)) return;
            this.text = text;
            this.color = color;
            revalidate();
            repaint();
        }
        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(fm.stringWidth(text) + 42, 28);
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 36));
            g2.fillRoundRect(0, 0, w - 1, h - 1, h, h);
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 110));
            g2.drawRoundRect(0, 0, w - 1, h - 1, h, h);
            g2.setColor(color);
            g2.fillOval(12, h / 2 - 3, 7, 7);
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, 26, (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** Animated horizontal tank level gauge. */
    static class TankGauge extends JComponent {
        private final String name;
        private final double capacity;
        private final Color color;
        private double target, shown;
        private final Timer anim;

        TankGauge(String name, double capacity, Color color) {
            this.name = name;
            this.capacity = capacity;
            this.color = color;
            this.target = capacity;
            this.shown = capacity;
            setPreferredSize(new Dimension(280, 62));
            anim = new Timer(16, e -> {
                double d = target - shown;
                if (Math.abs(d) < 0.5) {
                    shown = target;
                    ((Timer) e.getSource()).stop();
                } else {
                    shown += d * 0.18;
                }
                repaint();
            });
        }
        double getTarget() { return target; }
        void setLevel(double litres) {
            target = Math.max(0, litres);
            if (!anim.isRunning()) anim.start();
        }
        void reset() {
            anim.stop();
            target = capacity;
            shown = capacity;
            repaint();
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            double pct = Math.max(0, Math.min(1, shown / capacity));
            boolean low = pct < 0.20;
            Color c = low ? DANGER : color;

            g2.setColor(c);
            g2.fillOval(0, 4, 10, 10);
            g2.setFont(ui(Font.BOLD, 14));
            g2.setColor(TEXT);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(name, 18, fm.getAscent());

            String right = String.format(Locale.US, "%,.0f L", shown);
            g2.setFont(ui(Font.PLAIN, 12));
            g2.setColor(MUTED);
            fm = g2.getFontMetrics();
            g2.drawString(right, w - fm.stringWidth(right), 14);

            int by = 24, bh = 14;
            RoundRectangle2D track = new RoundRectangle2D.Double(0, by, w, bh, bh, bh);
            g2.setColor(INPUT);
            g2.fill(track);
            Shape old = g2.getClip();
            g2.clip(track);
            int fw = (int) Math.round(w * pct);
            g2.setPaint(new GradientPaint(0, by, c.darker(), Math.max(fw, 1), by, c));
            g2.fillRect(0, by, fw, bh);
            g2.setClip(old);

            g2.setFont(ui(Font.PLAIN, 11));
            g2.setColor(low ? DANGER : MUTED);
            String s = String.format(Locale.US, "%.1f%% of %,d L capacity%s", pct * 100, (long) capacity,
                    low ? "  -  LOW LEVEL" : "");
            g2.drawString(s, 0, by + bh + 15);
            g2.dispose();
        }
    }

    /** Horizontal bar chart: revenue per pump. */
    static class BarChart extends JComponent {
        private final Map<String, Double> values = new LinkedHashMap<>();
        private final Map<String, Color> colors = new LinkedHashMap<>();
        BarChart() { setPreferredSize(new Dimension(280, 170)); }
        void addBar(String label, Color c) { values.put(label, 0.0); colors.put(label, c); }
        void addValue(String label, double v) {
            values.merge(label, v, Double::sum);
            repaint();
        }
        void reset() {
            for (String k : values.keySet()) values.put(k, 0.0);
            repaint();
        }
        @Override protected void paintComponent(Graphics g) {
            int n = values.size();
            if (n == 0) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            double max = 1;
            for (double v : values.values()) max = Math.max(max, v);
            int w = getWidth(), h = getHeight();
            int rowH = Math.min(46, h / n);
            int labelW = 70, valueW = 92;
            int barX = labelW;
            int barW = Math.max(10, w - labelW - valueW);
            int i = 0;
            for (Map.Entry<String, Double> e : values.entrySet()) {
                int cy = i * rowH + rowH / 2;
                g2.setFont(ui(Font.PLAIN, 12));
                g2.setColor(MUTED);
                g2.drawString(e.getKey(), 0, cy + 4);

                RoundRectangle2D track = new RoundRectangle2D.Double(barX, cy - 6, barW, 12, 12, 12);
                g2.setColor(INPUT);
                g2.fill(track);
                Color c = colors.get(e.getKey());
                int fw = (int) Math.round(barW * (e.getValue() / max));
                if (fw > 0) {
                    Shape old = g2.getClip();
                    g2.clip(track);
                    g2.setPaint(new GradientPaint(barX, 0, c.darker(), barX + Math.max(fw, 1), 0, c));
                    g2.fillRect(barX, cy - 6, fw, 12);
                    g2.setClip(old);
                }
                String val = money(e.getValue());
                g2.setFont(ui(Font.BOLD, 12));
                g2.setColor(TEXT);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(val, w - fm.stringWidth(val), cy + 4);
                i++;
            }
            g2.dispose();
        }
    }

    /** Slim rounded progress line. */
    static class ThinProgress extends JComponent {
        private double fraction;
        ThinProgress() { setPreferredSize(new Dimension(100, 8)); }
        void setFraction(double f) {
            fraction = Math.max(0, Math.min(1, f));
            repaint();
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            RoundRectangle2D track = new RoundRectangle2D.Double(0, 0, w, h, h, h);
            g2.setColor(INPUT);
            g2.fill(track);
            int fw = (int) Math.round(w * fraction);
            if (fw > 0) {
                g2.clip(track);
                g2.setPaint(new GradientPaint(0, 0, new Color(0x0891B2), Math.max(fw, 1), 0, ACCENT));
                g2.fillRect(0, 0, fw, h);
            }
            g2.dispose();
        }
    }

    /** Logo mark drawn in code (no image files needed). */
    static class Logo extends JComponent {
        Logo() { setPreferredSize(new Dimension(44, 44)); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setPaint(new GradientPaint(0, 0, ACCENT, w, h, new Color(0x3B82F6)));
            g2.fillRoundRect(0, 0, w, h, 14, 14);

            double cx = w / 2.0, cy = h / 2.0, r = w * 0.2;
            Area drop = new Area(new Ellipse2D.Double(cx - r, cy + 0.4 * r - r, 2 * r, 2 * r));
            Path2D.Double tri = new Path2D.Double();
            tri.moveTo(cx, cy - 1.5 * r);
            tri.lineTo(cx + 0.85 * r, cy - 0.126 * r);
            tri.lineTo(cx - 0.85 * r, cy - 0.126 * r);
            tri.closePath();
            drop.add(new Area(tri));
            g2.setColor(Color.WHITE);
            g2.fill(drop);
            g2.dispose();
        }
    }

    /** Thin dark scroll bar. */
    static class SlimScrollBarUI extends BasicScrollBarUI {
        @Override protected void configureScrollBarColors() {
            thumbColor = new Color(0x34456B);
            trackColor = SURFACE;
        }
        private JButton zeroButton() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            return b;
        }
        @Override protected JButton createDecreaseButton(int orientation) { return zeroButton(); }
        @Override protected JButton createIncreaseButton(int orientation) { return zeroButton(); }
        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(SURFACE);
            g.fillRect(r.x, r.y, r.width, r.height);
        }
        @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.width <= 0 || r.height <= 0) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isThumbRollover() || isDragging ? ACCENT : new Color(0x34456B));
            g2.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 8, 8);
            g2.dispose();
        }
    }

    /** Table body cells: zebra rows, coloured fuel type, right-aligned numbers. */
    static class TxnRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            super.getTableCellRendererComponent(t, v, false, false, row, col);
            setBorder(new EmptyBorder(0, 14, 0, 14));
            setBackground(sel ? SELECT : (row % 2 == 0 ? SURFACE : ZEBRA));
            setForeground(TEXT);
            setFont(ui(Font.PLAIN, 13));
            setHorizontalAlignment(col >= 4 ? SwingConstants.RIGHT : SwingConstants.LEFT);
            if (col == 0) setForeground(MUTED);
            if (col == 1) { setFont(mono(Font.PLAIN, 13)); setForeground(ACCENT); }
            if (col == 3) { setFont(ui(Font.BOLD, 13)); setForeground(fuelColor(String.valueOf(v))); }
            if (col == 5) setFont(ui(Font.BOLD, 13));
            return this;
        }
    }

    /** Table header cells. */
    static class HeaderRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            super.getTableCellRendererComponent(t, v, false, false, row, col);
            setOpaque(true);
            setBackground(HEADER_BG);
            setForeground(MUTED);
            setFont(ui(Font.BOLD, 11));
            setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, LINE), new EmptyBorder(0, 14, 0, 14)));
            setHorizontalAlignment(col >= 4 ? SwingConstants.RIGHT : SwingConstants.LEFT);
            return this;
        }
    }

    // =====================================================================
    // Entry point
    // =====================================================================
    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }
        catch (Exception e) {}

        SwingUtilities.invokeLater(() -> {
            PetrolBunkSystem dashboard = new PetrolBunkSystem();
            dashboard.setLocationRelativeTo(null);
            dashboard.setVisible(true);
        });
    }
}