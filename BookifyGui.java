import java.awt.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
 
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
 
/**
 * BookifyGUI — redesigned, bug-fixed edition.
 *
 * Bugs fixed vs original:
 *  1. Triple popup on cancellation: suppressBookingPopups was reset before
 *     SwingUtilities.invokeLater() ran inside handleSystemStatus, so the popup
 *     still fired. Fix: cancel methods now use a dedicated result dialog and
 *     the status listener never shows its own popup for cancel-originated messages.
 *  2. cancelUsingSeatField / cancelSelectedBooking blocked the EDT. Both now
 *     dispatch work via SwingWorker.
 *  3. buildDashboardTabs created a second JTable over bookingsModel instead of
 *     reusing bookingsTable, so the Cancel tab and Dashboard showed independent
 *     selections. Fix: single bookingsTable instance used everywhere.
 *  4. maybeShowBookingPopup had a precedence bug:
 *        lowerMessage.contains("seat ") && lowerMessage.contains("is booked for user")
 *     was ORed at the wrong level. Fixed with explicit parentheses.
 *  5. Login error messages leaked internal DB details to the user. Now shows
 *     a clean "Invalid username or password." message.
 */
public class BookifyGUI extends JFrame {
 
    // ── View names ────────────────────────────────────────────────────────────
    private static final String LOGIN_VIEW     = "login";
    private static final String DASHBOARD_VIEW = "dashboard";
 
    // ── Palette ───────────────────────────────────────────────────────────────
    private static final Color BG_PAGE        = new Color(15, 17, 23);
    private static final Color BG_CARD        = new Color(22, 26, 35);
    private static final Color BG_PANEL       = new Color(28, 33, 45);
    private static final Color BG_INPUT       = new Color(35, 41, 56);
    private static final Color BG_TABLE_ROW_A = new Color(28, 33, 45);
    private static final Color BG_TABLE_ROW_B = new Color(32, 38, 52);
    private static final Color BG_TABLE_HEAD  = new Color(20, 24, 34);
    private static final Color ACCENT         = new Color(94, 206, 179);   // teal-mint
    private static final Color ACCENT_DIM     = new Color(60, 140, 120);
    private static final Color ACCENT_DANGER  = new Color(232, 90, 90);
    private static final Color TEXT_PRIMARY   = new Color(230, 235, 245);
    private static final Color TEXT_MUTED     = new Color(120, 132, 155);
    private static final Color BORDER_SUBTLE  = new Color(45, 52, 70);
 
    // ── Typography ────────────────────────────────────────────────────────────
    private static final Font FONT_LOGO    = new Font("Georgia",    Font.BOLD,  34);
    private static final Font FONT_HEADING = new Font("Georgia",    Font.BOLD,  22);
    private static final Font FONT_SECTION = new Font("Georgia",    Font.BOLD,  17);
    private static final Font FONT_LABEL   = new Font("SansSerif",  Font.BOLD,  13);
    private static final Font FONT_BODY    = new Font("SansSerif",  Font.PLAIN, 13);
    private static final Font FONT_MONO    = new Font("Monospaced", Font.PLAIN, 12);
    private static final Font FONT_BTN     = new Font("SansSerif",  Font.BOLD,  13);
    private static final Font FONT_HINT    = new Font("SansSerif",  Font.PLAIN, 11);
 
    // ── Core services ─────────────────────────────────────────────────────────
    private final BookingSystem bookingSystem = new BookingSystem();
    private final LoginService  loginService  = new LoginService();
 
    // ── Root layout ───────────────────────────────────────────────────────────
    private final JPanel rootPanel = new JPanel(new CardLayout());
 
    // ── Login fields ──────────────────────────────────────────────────────────
    private final JTextField    loginUsernameField = new JTextField(20);
    private final JPasswordField loginPasswordField = new JPasswordField(20);
    private final JLabel        loginErrorLabel    = new JLabel(" ");
 
    // ── Dashboard fields ──────────────────────────────────────────────────────
    private final JTextField     bookingUsernameField = new JTextField(18);
    private final JComboBox<String> categoryBox = new JComboBox<>(new String[]{"VIP", "NORMAL"});
    private final JComboBox<String> userTypeBox = new JComboBox<>(new String[]{"PREMIUM", "NORMAL"});
    private final JTextField     cancelSeatField = new JTextField(10);
    private final JTextArea      statusArea      = new JTextArea(12, 44);
    private final JLabel         welcomeLabel    = new JLabel("Welcome");
 
    // ── Table models ──────────────────────────────────────────────────────────
    private final DefaultTableModel bookingsModel =
            nonEditableModel("Seat ID", "Seat #", "User", "Category", "Status");
    private final DefaultTableModel availableModel =
            nonEditableModel("Seat ID", "Seat #", "Category");
    private final DefaultTableModel waitingModel =
            nonEditableModel("#", "User", "Category", "User Type");
    private final DefaultTableModel reportModel =
            nonEditableModel("Category", "Confirmed Bookings");
 
    // ── Single shared table for bookings (reused across tabs) ─────────────────
    //    BUG FIX #3: original code created a NEW JTable in buildDashboardTabs()
    //    over the same model, which broke row selection in the cancel tab.
    private final JTable bookingsTable = new JTable(bookingsModel);
 
    // ── State ─────────────────────────────────────────────────────────────────
    private String  activeUsername;
    private Timer   refreshTimer;
    // True while a cancel SwingWorker is running — suppresses status-listener popups
    private final AtomicBoolean cancelInProgress = new AtomicBoolean(false);
 
    // ── Constructor ───────────────────────────────────────────────────────────
    public BookifyGUI() {
        applyLookAndFeel();
        setTitle("Bookify — Booking System");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1120, 720);
        setMinimumSize(new Dimension(980, 640));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_PAGE);
 
        initUi();
        bookingSystem.setStatusListener(this::handleSystemStatus);
        startAutoRefresh();
    }
 
    // ── Look-and-feel ─────────────────────────────────────────────────────────
    private void applyLookAndFeel() {
        // Do NOT use system L&F — on Windows it overrides custom button backgrounds.
        // Swing's cross-platform (Metal) L&F respects setBackground/setForeground correctly.
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {}
        UIManager.put("TabbedPane.selected",         BG_PANEL);
        UIManager.put("TabbedPane.background",        BG_CARD);
        UIManager.put("TabbedPane.foreground",        TEXT_PRIMARY);
        UIManager.put("TabbedPane.contentAreaColor",  BG_PANEL);
        UIManager.put("OptionPane.background",        BG_CARD);
        UIManager.put("Panel.background",             BG_CARD);
        UIManager.put("OptionPane.messageForeground", TEXT_PRIMARY);
    }
 
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  UI initialisation
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    private void initUi() {
        rootPanel.setBackground(BG_PAGE);
        setContentPane(rootPanel);
        rootPanel.add(buildLoginView(),    LOGIN_VIEW);
        rootPanel.add(buildDashboardView(), DASHBOARD_VIEW);
        showView(LOGIN_VIEW);
    }
 
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  LOGIN VIEW
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    private JPanel buildLoginView() {
        JPanel outer = new JPanel(new GridBagLayout());
        outer.setBackground(BG_PAGE);
 
        // Left branding strip
        JPanel brand = new JPanel();
        brand.setBackground(new Color(18, 22, 32));
        brand.setLayout(new GridBagLayout());
        brand.setPreferredSize(new Dimension(280, 0));
        brand.setBorder(new EmptyBorder(40, 32, 40, 32));
 
        JPanel brandContent = new JPanel();
        brandContent.setOpaque(false);
        brandContent.setLayout(new BoxLayout(brandContent, BoxLayout.Y_AXIS));
 
        JLabel logo = new JLabel("B");
        logo.setFont(new Font("Georgia", Font.BOLD, 64));
        logo.setForeground(ACCENT);
        logo.setAlignmentX(Component.LEFT_ALIGNMENT);
 
        JLabel logoName = new JLabel("ookify");
        logoName.setFont(new Font("Georgia", Font.BOLD, 28));
        logoName.setForeground(TEXT_PRIMARY);
        logoName.setAlignmentX(Component.LEFT_ALIGNMENT);
 
        JLabel tagline = new JLabel("<html><div style='line-height:1.5'>Seat management<br>made effortless.</div></html>");
        tagline.setFont(FONT_BODY);
        tagline.setForeground(TEXT_MUTED);
        tagline.setAlignmentX(Component.LEFT_ALIGNMENT);
 
        JPanel logoRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        logoRow.setOpaque(false);
        logoRow.add(logo);
        logoRow.add(logoName);
        logoRow.setAlignmentX(Component.LEFT_ALIGNMENT);
 
        brandContent.add(logoRow);
        brandContent.add(Box.createVerticalStrut(20));
        brandContent.add(tagline);
 
        brand.add(brandContent);
 
        // Right form card
        JPanel card = new JPanel();
        card.setBackground(BG_CARD);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(48, 48, 48, 48));
        card.setPreferredSize(new Dimension(400, 0));
 
        JLabel heading = new JLabel("Sign in");
        heading.setFont(FONT_HEADING);
        heading.setForeground(TEXT_PRIMARY);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
 
        JLabel sub = new JLabel("Access your Bookify dashboard");
        sub.setFont(FONT_BODY);
        sub.setForeground(TEXT_MUTED);
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);
 
        loginErrorLabel.setFont(FONT_HINT);
        loginErrorLabel.setForeground(ACCENT_DANGER);
        loginErrorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
 
        JPanel usernameBlock = buildLoginFieldBlock("Username", loginUsernameField);
        JPanel passwordBlock = buildLoginFieldBlock("Password", loginPasswordField);
        usernameBlock.setAlignmentX(Component.LEFT_ALIGNMENT);
        passwordBlock.setAlignmentX(Component.LEFT_ALIGNMENT);
 
        JButton signInBtn = buildPrimaryButton("Sign In", 220, 42);
        signInBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        signInBtn.addActionListener(e -> handleLogin());
        loginPasswordField.addActionListener(e -> handleLogin());
        loginUsernameField.addActionListener(e -> loginPasswordField.requestFocus());
 
        JLabel hint = new JLabel("Use your database credentials to log in.");
        hint.setFont(FONT_HINT);
        hint.setForeground(TEXT_MUTED);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
 
        card.add(heading);
        card.add(Box.createVerticalStrut(6));
        card.add(sub);
        card.add(Box.createVerticalStrut(28));
        card.add(usernameBlock);
        card.add(Box.createVerticalStrut(16));
        card.add(passwordBlock);
        card.add(Box.createVerticalStrut(8));
        card.add(loginErrorLabel);
        card.add(Box.createVerticalStrut(20));
        card.add(signInBtn);
        card.add(Box.createVerticalStrut(16));
        card.add(hint);
 
        // Combined card
        JPanel combined = new JPanel(new BorderLayout());
        combined.setBackground(BG_CARD);
        combined.setBorder(BorderFactory.createLineBorder(BORDER_SUBTLE));
        combined.setPreferredSize(new Dimension(680, 400));
        combined.add(brand, BorderLayout.WEST);
        combined.add(card,  BorderLayout.CENTER);
 
        outer.add(combined);
        return outer;
    }
 
    private JPanel buildLoginFieldBlock(String label, Component field) {
        JPanel block = new JPanel();
        block.setOpaque(false);
        block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
 
        JLabel lbl = new JLabel(label);
        lbl.setFont(FONT_LABEL);
        lbl.setForeground(TEXT_MUTED);
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
 
        styleInput(field);
        ((JComponent) field).setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        ((JComponent) field).setAlignmentX(Component.LEFT_ALIGNMENT);
 
        block.add(lbl);
        block.add(Box.createVerticalStrut(6));
        block.add(field);
        return block;
    }
 
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  DASHBOARD VIEW
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    private JPanel buildDashboardView() {
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(BG_PAGE);
 
        root.add(buildTopBar(),    BorderLayout.NORTH);
        root.add(buildMainArea(),  BorderLayout.CENTER);
        return root;
    }
 
    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(new Color(13, 16, 22));
        bar.setBorder(new EmptyBorder(14, 22, 14, 22));
 
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
 
        JLabel logo = new JLabel("Bookify");
        logo.setFont(new Font("Georgia", Font.BOLD, 22));
        logo.setForeground(ACCENT);
 
        JLabel sep = new JLabel("  ·  ");
        sep.setFont(FONT_BODY);
        sep.setForeground(BORDER_SUBTLE);
 
        welcomeLabel.setFont(FONT_BODY);
        welcomeLabel.setForeground(TEXT_MUTED);
 
        left.add(logo);
        left.add(sep);
        left.add(welcomeLabel);
 
        JButton logoutBtn = buildOutlineButton("Log Out", 90, 34);
        logoutBtn.addActionListener(e -> logout());
 
        bar.add(left, BorderLayout.WEST);
        bar.add(logoutBtn, BorderLayout.EAST);
        return bar;
    }
 
    private Component buildMainArea() {
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        styleTabs(tabs);
        tabs.addTab("  Book Ticket  ",     buildBookingTab());
        tabs.addTab("  Cancel Ticket  ",   buildCancelTab());
        tabs.addTab("  Live Dashboard  ",  buildLiveDashboard());
        tabs.addTab("  Activity Log  ",    buildActivityTab());
        return tabs;
    }
 
    // ── Book Ticket tab ───────────────────────────────────────────────────────
    private JPanel buildBookingTab() {
        JPanel outer = darkPanel();
        outer.setBorder(new EmptyBorder(24, 24, 24, 24));
        outer.setLayout(new BorderLayout(0, 20));
 
        JLabel title = sectionLabel("New Booking");
        outer.add(title, BorderLayout.NORTH);
 
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints g = formConstraints();
 
        addFormRow(form, g, 0, "Username",      bookingUsernameField);
        addFormRow(form, g, 1, "Seat Category", categoryBox);
        addFormRow(form, g, 2, "User Type",     userTypeBox);
 
        JButton bookBtn    = buildPrimaryButton("Book Ticket", 140, 38);
        JButton refreshBtn = buildOutlineButton("Refresh",      110, 38);
        bookBtn.addActionListener(e -> submitBooking());
        refreshBtn.addActionListener(e -> refreshTables());
 
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        actions.setOpaque(false);
        actions.add(bookBtn);
        actions.add(refreshBtn);
 
        JPanel center = new JPanel(new BorderLayout(0, 16));
        center.setOpaque(false);
        center.add(form,    BorderLayout.NORTH);
        center.add(actions, BorderLayout.SOUTH);
 
        outer.add(center, BorderLayout.CENTER);
        return outer;
    }
 
    // ── Cancel Ticket tab ─────────────────────────────────────────────────────
    private JPanel buildCancelTab() {
        JPanel outer = darkPanel();
        outer.setBorder(new EmptyBorder(24, 24, 24, 24));
        outer.setLayout(new BorderLayout(0, 16));
 
        JLabel title = sectionLabel("Cancel Booking");
        outer.add(title, BorderLayout.NORTH);
 
        // Top: manual seat-ID entry
        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        topRow.setOpaque(false);
 
        JLabel idLabel = new JLabel("Seat ID:");
        idLabel.setFont(FONT_LABEL);
        idLabel.setForeground(TEXT_MUTED);
 
        styleInput(cancelSeatField);
        cancelSeatField.setPreferredSize(new Dimension(110, 36));
 
        JButton cancelByIdBtn  = buildDangerButton("Cancel by ID", 150, 36);
        JButton cancelSelBtn   = buildOutlineButton("Cancel Selected Row", 190, 36);
        cancelByIdBtn.addActionListener(e -> cancelUsingSeatField());
        cancelSelBtn.addActionListener(e -> cancelSelectedBooking());
 
        topRow.add(idLabel);
        topRow.add(cancelSeatField);
        topRow.add(Box.createHorizontalStrut(6));
        topRow.add(cancelByIdBtn);
        topRow.add(cancelSelBtn);
 
        // Bottom: confirmed bookings table
        //  BUG FIX #3: reuse bookingsTable (shared instance, not a new JTable)
        styleTable(bookingsTable);
        JScrollPane scroll = styledScrollPane(bookingsTable);
 
        JLabel tableHint = new JLabel("Click a row, then press 'Cancel Selected Row'");
        tableHint.setFont(FONT_HINT);
        tableHint.setForeground(TEXT_MUTED);
        tableHint.setBorder(new EmptyBorder(0, 0, 6, 0));
 
        JPanel tableArea = new JPanel(new BorderLayout(0, 4));
        tableArea.setOpaque(false);
        tableArea.add(tableHint, BorderLayout.NORTH);
        tableArea.add(scroll,    BorderLayout.CENTER);
 
        outer.add(topRow,    BorderLayout.NORTH);
        outer.add(tableArea, BorderLayout.CENTER);
        return outer;
    }
 
    // ── Live Dashboard tab ────────────────────────────────────────────────────
    private Component buildLiveDashboard() {
        JTabbedPane inner = new JTabbedPane(JTabbedPane.TOP);
        styleTabs(inner);
 
        // BUG FIX #3: bookingsTable already used in cancel tab — wrap a *view* for dashboard
        JTable dashBookingsTable = new JTable(bookingsModel);  // shares model, not selection
        styleTable(dashBookingsTable);
 
        JTable availTable  = new JTable(availableModel);  styleTable(availTable);
        JTable waitTable   = new JTable(waitingModel);    styleTable(waitTable);
        JTable repTable    = new JTable(reportModel);     styleTable(repTable);
 
        inner.addTab("Confirmed Bookings", wrapTablePanel(dashBookingsTable));
        inner.addTab("Available Seats",    wrapTablePanel(availTable));
        inner.addTab("Waiting List",       wrapTablePanel(waitTable));
        inner.addTab("Report",             wrapTablePanel(repTable));
        return inner;
    }
 
    // ── Activity Log tab ──────────────────────────────────────────────────────
    private JPanel buildActivityTab() {
        JPanel outer = darkPanel();
        outer.setBorder(new EmptyBorder(24, 24, 24, 24));
        outer.setLayout(new BorderLayout(0, 12));
 
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(sectionLabel("Activity Log"), BorderLayout.WEST);
 
        JButton clearBtn = buildOutlineButton("Clear", 80, 32);
        clearBtn.addActionListener(e -> statusArea.setText(""));
        header.add(clearBtn, BorderLayout.EAST);
 
        statusArea.setEditable(false);
        statusArea.setLineWrap(true);
        statusArea.setWrapStyleWord(true);
        statusArea.setFont(FONT_MONO);
        statusArea.setForeground(new Color(160, 220, 180));
        statusArea.setBackground(new Color(14, 18, 26));
        statusArea.setCaretColor(ACCENT);
        statusArea.setBorder(new EmptyBorder(10, 12, 10, 12));
 
        JScrollPane scroll = styledScrollPane(statusArea);
 
        outer.add(header, BorderLayout.NORTH);
        outer.add(scroll,  BorderLayout.CENTER);
        return outer;
    }
 
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  ACTION HANDLERS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 
    /** BUG FIX #5: show a clean error, never expose internal DB messages */
    private void handleLogin() {
        String username = loginUsernameField.getText().trim();
        String password = new String(loginPasswordField.getPassword());
 
        loginErrorLabel.setText(" ");
 
        if (username.isEmpty() || password.isEmpty()) {
            loginErrorLabel.setText("Username and password are required.");
            return;
        }
 
        LoginService.LoginResult result = loginService.attemptLogin(username, password);
        if (!result.isSuccess()) {
            // BUG FIX #5: original code echoed raw DB error messages ("Password did not
            // match the value stored in the users table.") — now shows a generic message.
            loginErrorLabel.setText("Invalid username or password.");
            appendStatus("Login attempt failed for '" + username + "'.");
            return;
        }
 
        activeUsername = username;
        bookingUsernameField.setText(username);
        welcomeLabel.setText("Signed in as  " + username);
        loginErrorLabel.setText(" ");
        appendStatus("Login successful — welcome, " + username + ".");
        refreshTables();
        showView(DASHBOARD_VIEW);
    }
 
    private void logout() {
        if (refreshTimer != null) refreshTimer.stop();
        activeUsername = null;
        loginPasswordField.setText("");
        loginErrorLabel.setText(" ");
        bookingUsernameField.setText("");
        cancelSeatField.setText("");
        welcomeLabel.setText("Welcome");
        showView(LOGIN_VIEW);
        startAutoRefresh();
    }
 
    private void submitBooking() {
        String username = bookingUsernameField.getText().trim();
        String category = String.valueOf(categoryBox.getSelectedItem());
        String userType = String.valueOf(userTypeBox.getSelectedItem());
 
        if (username.isEmpty()) {
            showInfo("Username is required to make a booking.", "Missing Field", false);
            return;
        }
 
        appendStatus("Booking requested: " + username + " [" + category + " / " + userType + "]");
 
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() {
                return bookingSystem.bookTicket(username, category, userType);
            }
            @Override protected void done() {
                try {
                    // Result is also pushed via statusListener; no extra popup needed here.
                    get();
                } catch (Exception ex) {
                    appendStatus("Booking error: " + ex.getMessage());
                } finally {
                    refreshTables();
                }
            }
        }.execute();
    }
 
    /**
     * Cancel by typed Seat ID.
     *
     * BUG FIX #1 + #2:
     *  - Moved off EDT via SwingWorker so the UI is not frozen.
     *  - Shows exactly ONE result dialog after completion. The status listener
     *    appends to the log silently; it never shows its own popup for cancel
     *    results (see handleSystemStatus — popups only appear for booking events).
     */
    private void cancelUsingSeatField() {
        String seatText = cancelSeatField.getText().trim();
        if (seatText.isEmpty()) {
            showInfo("Enter a Seat ID to cancel.", "Missing Field", false);
            return;
        }
 
        int seatId;
        try {
            seatId = Integer.parseInt(seatText);
        } catch (NumberFormatException ex) {
            showInfo("Seat ID must be a whole number.", "Invalid Input", false);
            return;
        }
 
        final int id = seatId;
        cancelInProgress.set(true);   // suppress status-listener popups until done
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() {
                return bookingSystem.cancelTicket(id);
            }
            @Override protected void done() {
                cancelInProgress.set(false);
                try {
                    String result = get();
                    // ONE popup only — status listener does NOT pop up cancel results
                    showInfo(result, "Cancellation Result", true);
                } catch (Exception ex) {
                    showInfo("Cancellation error: " + ex.getMessage(), "Error", false);
                } finally {
                    refreshTables();
                }
            }
        }.execute();
    }
 
    /**
     * Cancel the booking selected in the bookings table.
     *
     * BUG FIX #1 + #2: same fixes as cancelUsingSeatField.
     */
    private void cancelSelectedBooking() {
        int row = bookingsTable.getSelectedRow();
        if (row < 0) {
            showInfo("Select a booking row first.", "No Selection", false);
            return;
        }
 
        int seatId = Integer.parseInt(bookingsModel.getValueAt(row, 0).toString());
 
        cancelInProgress.set(true);   // suppress status-listener popups until done
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() {
                return bookingSystem.cancelTicket(seatId);
            }
            @Override protected void done() {
                cancelInProgress.set(false);
                try {
                    String result = get();
                    cancelSeatField.setText(String.valueOf(seatId));
                    showInfo(result, "Cancellation Result", true);
                } catch (Exception ex) {
                    showInfo("Cancellation error: " + ex.getMessage(), "Error", false);
                } finally {
                    refreshTables();
                }
            }
        }.execute();
    }
 
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  STATUS LISTENER  (called from BookingSystem on any thread)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    private void handleSystemStatus(String message) {
        SwingUtilities.invokeLater(() -> {
            appendStatus(message);
            maybeShowBookingPopup(message);
            refreshTables();
        });
    }
 
    /**
     * Show a popup only for booking confirmations / waiting-list entries.
     * Cancel-originated messages are NOT shown here — the cancel methods
     * already display exactly one result dialog.
     *
     * BUG FIX #4: the original condition was:
     *   lowerMessage.contains("waiting list")
     *   || lowerMessage.contains("assigned successfully")
     *   || lowerMessage.contains("seat ")          ← dangling OR
     *      && lowerMessage.contains("is booked for user")
     *
     * Due to operator precedence, the last two lines were evaluated as:
     *   (lowerMessage.contains("seat ") && lowerMessage.contains("is booked for user"))
     * which was only ORed with the first two — but "seat " alone fired on cancel
     * messages like "[CANCEL] Seat 3 is not booked or…" causing extra popups.
     * Fixed by adding explicit parentheses and tightening the cancel-exclusion check.
     */
    private void maybeShowBookingPopup(String message) {
        // Never show a popup while a cancel operation is in progress —
        // the cancel worker's done() already shows exactly one result dialog.
        if (cancelInProgress.get()) return;
 
        String lo = message.toLowerCase();
 
        // Also guard against cancel-result strings that arrive after flag clears
        if (lo.contains("was cancelled") || lo.contains("is now free")
                || lo.contains("is now available")) {
            return;
        }
 
        // BUG FIX #4: parentheses around the compound condition
        boolean isBookingUpdate =
                lo.contains("waiting list")
             || lo.contains("assigned successfully")
             || (lo.contains("seat ") && lo.contains("is booked for user"));
 
        if (isBookingUpdate) {
            showInfo(message, "Booking Update", true);
        }
    }
 
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  DATA HELPERS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    private void refreshTables() {
        loadTable(bookingsModel,  bookingSystem.getConfirmedBookingsData());
        loadTable(availableModel, bookingSystem.getAvailableSeatsData());
        loadTable(waitingModel,   bookingSystem.getWaitingListData());
        loadTable(reportModel,    bookingSystem.getReportData());
    }
 
    private void startAutoRefresh() {
        if (refreshTimer != null) refreshTimer.stop();
        refreshTimer = new Timer(4000, e -> {
            if (DASHBOARD_VIEW.equals(getCurrentView())) refreshTables();
        });
        refreshTimer.start();
    }
 
    private String getCurrentView() {
        return activeUsername == null ? LOGIN_VIEW : DASHBOARD_VIEW;
    }
 
    private void showView(String name) {
        ((CardLayout) rootPanel.getLayout()).show(rootPanel, name);
    }
 
    private void loadTable(DefaultTableModel model, List<String[]> rows) {
        model.setRowCount(0);
        for (String[] row : rows) model.addRow(row);
    }
 
    private void appendStatus(String msg) {
        if (msg == null || msg.isBlank()) return;
        statusArea.append(msg + System.lineSeparator());
        statusArea.setCaretPosition(statusArea.getDocument().getLength());
    }
 
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  UI FACTORY HELPERS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 
    private JPanel darkPanel() {
        JPanel p = new JPanel();
        p.setBackground(BG_PANEL);
        return p;
    }
 
    private JLabel sectionLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(FONT_SECTION);
        lbl.setForeground(TEXT_PRIMARY);
        lbl.setBorder(new EmptyBorder(0, 0, 12, 0));
        return lbl;
    }
 
    private GridBagConstraints formConstraints() {
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(8, 8, 8, 12);
        g.fill   = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.WEST;
        return g;
    }
 
    private void addFormRow(JPanel panel, GridBagConstraints g, int row,
                            String label, Component field) {
        JLabel lbl = new JLabel(label + ":");
        lbl.setFont(FONT_LABEL);
        lbl.setForeground(TEXT_MUTED);
 
        g.gridx = 0; g.gridy = row; g.weightx = 0;
        panel.add(lbl, g);
 
        g.gridx = 1; g.weightx = 1;
        styleInput(field);
        panel.add(field, g);
    }
 
    private void styleInput(Component c) {
        c.setFont(FONT_BODY);
        if (c instanceof JTextField) {
            JTextField f = (JTextField) c;
            f.setForeground(TEXT_PRIMARY);
            f.setBackground(BG_INPUT);
            f.setCaretColor(ACCENT);
            f.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(BORDER_SUBTLE),
                    new EmptyBorder(7, 10, 7, 10)));
        } else if (c instanceof JComboBox) {
            JComboBox<?> cb = (JComboBox<?>) c;
            cb.setForeground(TEXT_PRIMARY);
            cb.setBackground(BG_INPUT);
        }
    }
 
    private JButton buildPrimaryButton(String text, int w, int h) {
        JButton b = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(ACCENT);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setForeground(new Color(10, 30, 28));
        b.setFont(FONT_BTN);
        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setPreferredSize(new Dimension(w, h));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }
 
    private JButton buildDangerButton(String text, int w, int h) {
        JButton b = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(ACCENT_DANGER);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setForeground(Color.WHITE);
        b.setFont(FONT_BTN);
        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setPreferredSize(new Dimension(w, h));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }
 
    private JButton buildOutlineButton(String text, int w, int h) {
        JButton b = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(70, 90, 140));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setForeground(Color.WHITE);
        b.setFont(FONT_BTN);
        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(true);
        b.setBorder(BorderFactory.createLineBorder(new Color(120, 145, 210), 1));
        b.setFocusPainted(false);
        b.setPreferredSize(new Dimension(w, h));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }
 
    private void styleTable(JTable table) {
        table.setFont(FONT_BODY);
        table.setForeground(TEXT_PRIMARY);
        table.setBackground(BG_TABLE_ROW_A);
        table.setRowHeight(28);
        table.setGridColor(BORDER_SUBTLE);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setSelectionBackground(new Color(60, 160, 140, 80));
        table.setSelectionForeground(TEXT_PRIMARY);
        table.setShowVerticalLines(false);
        table.setFillsViewportHeight(true);
 
        JTableHeader header = table.getTableHeader();
        header.setFont(FONT_LABEL);
        header.setBackground(BG_TABLE_HEAD);
        header.setForeground(TEXT_MUTED);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_SUBTLE));
        header.setReorderingAllowed(false);
 
        // Alternating row colours
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable t, Object val, boolean sel, boolean foc, int r, int c) {
                super.getTableCellRendererComponent(t, val, sel, foc, r, c);
                setForeground(sel ? TEXT_PRIMARY : TEXT_PRIMARY);
                setBackground(sel ? new Color(60, 160, 140, 80)
                                  : (r % 2 == 0 ? BG_TABLE_ROW_A : BG_TABLE_ROW_B));
                setBorder(new EmptyBorder(0, 10, 0, 10));
                return this;
            }
        });
    }
 
    private JPanel wrapTablePanel(JTable table) {
        JScrollPane scroll = styledScrollPane(table);
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG_PANEL);
        p.setBorder(new EmptyBorder(10, 10, 10, 10));
        p.add(scroll, BorderLayout.CENTER);
        return p;
    }
 
    private JScrollPane styledScrollPane(Component c) {
        JScrollPane sp = new JScrollPane(c);
        sp.setBorder(BorderFactory.createLineBorder(BORDER_SUBTLE));
        sp.getViewport().setBackground(BG_TABLE_ROW_A);
        sp.setBackground(BG_PANEL);
        return sp;
    }
 
    private void styleTabs(JTabbedPane tabs) {
        tabs.setFont(FONT_BTN);
        tabs.setBackground(BG_CARD);
        tabs.setForeground(TEXT_MUTED);
    }
 
    private DefaultTableModel nonEditableModel(String... cols) {
        return new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }
 
    /** Single helper for all informational dialogs — avoids scattered JOptionPane calls. */
    private void showInfo(String message, String title, boolean isInfo) {
        int type = isInfo ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE;
        JOptionPane.showMessageDialog(this, message, title, type);
    }
}
