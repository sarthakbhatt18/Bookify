import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
 
public class BookingSystem {
 
    private final LinkedList<String> waitingList = new LinkedList<>();
    private Consumer<String> statusListener;
 
    public synchronized void setStatusListener(Consumer<String> statusListener) {
        this.statusListener = statusListener;
    }
 
    private void emitStatus(String message) {
        Consumer<String> listener;
        synchronized (this) {
            listener = statusListener;
        }
        if (listener != null && message != null && !message.isBlank()) {
            listener.accept(message);
        }
    }
 
    public synchronized String bookTicket(String username, String category, String userType) {
 
        if (username == null || category == null || userType == null
                || username.isBlank() || category.isBlank() || userType.isBlank()) {
            String message = "[ERROR] Invalid input for booking.";
            System.out.println(message);
            emitStatus(message);
            return message;
        }
 
        category = category.toUpperCase().trim();
        userType = userType.toUpperCase().trim();
 
        if (!category.equals("VIP") && !category.equals("NORMAL")) {
            String message = "[ERROR] Category must be VIP or NORMAL.";
            System.out.println(message);
            emitStatus(message);
            return message;
        }
 
        try {
            Connection con = DBConnection.getConnection();
            StringBuilder updates = new StringBuilder();
 
            while (true) {
                PreparedStatement findSeat = con.prepareStatement(
                        "SELECT seat_id, seat_number FROM seats " +
                        "WHERE is_booked = false AND category = ? LIMIT 1");
                findSeat.setString(1, category);
                ResultSet rs = findSeat.executeQuery();
 
                if (rs.next()) {
                    int seatId = rs.getInt("seat_id");
                    String seatNumber = rs.getString("seat_number");
 
                    PreparedStatement lock = con.prepareStatement(
                            "UPDATE seats SET is_booked = true " +
                            "WHERE seat_id = ? AND is_booked = false");
                    lock.setInt(1, seatId);
                    if (lock.executeUpdate() == 0) {
                        continue;
                    }
 
                    PreparedStatement insert = con.prepareStatement(
                            "INSERT INTO bookings(user_name, seat_id, category, status) " +
                            "VALUES(?, ?, ?, 'CONFIRMED')");
                    insert.setString(1, username);
                    insert.setInt(2, seatId);
                    insert.setString(3, category);
                    insert.executeUpdate();
 
                    String bookedMessage = "Seat " + seatNumber + " is booked for user " + username
                            + " in " + category + " category.";
                    System.out.println("\n[BOOKED] " + username + " -> " + category
                            + " seat " + seatNumber + " (CONFIRMED)");
                    emitStatus(bookedMessage);
                    updates.append(bookedMessage);
 
                    waitingList.removeIf(entry -> entry.startsWith(username + "|"));
                    return updates.toString();
 
                } else {
                    String entry = username + "|" + category + "|" + userType;
                    if (!waitingList.contains(entry)) {
                        if (userType.equals("PREMIUM")) {
                            int insertIdx = 0;
                            for (int i = 0; i < waitingList.size(); i++) {
                                if (waitingList.get(i).endsWith("|PREMIUM")) {
                                    insertIdx = i + 1;
                                } else {
                                    break;
                                }
                            }
                            waitingList.add(insertIdx, entry);
                        } else {
                            waitingList.addLast(entry);
                        }
                        String waitingMessage = username + " added to the waiting list for "
                                + category + " seats as a " + userType + " user.";
                        System.out.println("\n[WAITING] " + username
                                + " (" + userType + ") added to waiting list.");
                        emitStatus(waitingMessage);
                        if (updates.length() > 0) {
                            updates.append(System.lineSeparator());
                        }
                        updates.append(waitingMessage);
                        printWaitingList();
                    }
 
                    try {
                        wait();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        String message = "[INFO] " + username + " booking interrupted.";
                        System.out.println(message);
                        emitStatus(message);
                        if (updates.length() > 0) {
                            updates.append(System.lineSeparator());
                        }
                        updates.append(message);
                        return updates.toString();
                    }
 
                    if (!waitingList.contains(entry)) {
                        String message = "Seat assigned successfully for user " + username
                                + " from the waiting list.";
                        System.out.println("[INFO] " + username
                                + " seat was assigned directly. Done.");
                        emitStatus(message);
                        if (updates.length() > 0) {
                            updates.append(System.lineSeparator());
                        }
                        updates.append(message);
                        return updates.toString();
                    }
 
                    System.out.println("\n[RETRY] " + username + " woke up, checking seat...");
                }
            }
 
        } catch (Exception e) {
            String message = "[ERROR] Booking failed for " + username + ": " + e.getMessage();
            System.out.println(message);
            emitStatus(message);
            return message;
        }
    }
 
    public synchronized String cancelTicket(int seatId) {
        Connection con = null;
        try {
            con = DBConnection.getConnection();
            con.setAutoCommit(false);
 
            PreparedStatement check = con.prepareStatement(
                    "SELECT category FROM seats WHERE seat_id = ? AND is_booked = true");
            check.setInt(1, seatId);
            ResultSet rs = check.executeQuery();
 
            if (!rs.next()) {
                String message = "[CANCEL] Seat " + seatId + " is not booked or does not exist.";
                System.out.println("\n" + message);
                emitStatus(message);
                con.rollback();
                return message;
            }
            String freedCategory = rs.getString("category");
 
            PreparedStatement cancelBook = con.prepareStatement(
                    "UPDATE bookings SET status = 'CANCELLED' " +
                    "WHERE seat_id = ? AND status = 'CONFIRMED'");
            cancelBook.setInt(1, seatId);
            if (cancelBook.executeUpdate() == 0) {
                String message = "[CANCEL] No active booking for seat " + seatId + ".";
                System.out.println("\n" + message);
                emitStatus(message);
                con.rollback();
                return message;
            }
 
            String nextEntry = findTopWaiter(freedCategory);
 
            if (nextEntry != null) {
                String nextUser = nextEntry.split("\\|")[0];
 
                PreparedStatement assign = con.prepareStatement(
                        "INSERT INTO bookings(user_name, seat_id, category, status) " +
                        "VALUES(?, ?, ?, 'CONFIRMED')");
                assign.setString(1, nextUser);
                assign.setInt(2, seatId);
                assign.setString(3, freedCategory);
                assign.executeUpdate();
 
                con.commit();
                waitingList.remove(nextEntry);
 
                PreparedStatement seatInfo = con.prepareStatement(
                        "SELECT seat_number FROM seats WHERE seat_id = ?");
                seatInfo.setInt(1, seatId);
                ResultSet seatResult = seatInfo.executeQuery();
                String seatNumber = seatResult.next()
                        ? seatResult.getString("seat_number")
                        : String.valueOf(seatId);
 
                String assignmentMessage = "[BOOKED] " + nextUser + " -> " + freedCategory
                        + " seat " + seatNumber + " (CONFIRMED)"
                        + " assigned from waiting list"
                        + (nextEntry.endsWith("|PREMIUM") ? " [PREMIUM PRIORITY]" : "");
                String cancelledMessage = "Booking for seat " + seatNumber + " was cancelled.";
                String reassignedMessage = "Seat " + seatNumber + " is now assigned to user "
                        + nextUser + " from the waiting list.";
 
                System.out.println("\n[CANCELLED] Seat " + seatId + " cancelled.");
                System.out.println(assignmentMessage);
                // Emit a single combined status so the GUI shows exactly one popup.
                emitStatus(cancelledMessage + " " + reassignedMessage);
 
                if (!waitingList.isEmpty()) {
                    System.out.println("[INFO] Notifying remaining "
                            + waitingList.size() + " waiting user(s)...");
                }
                notifyAll();
                return cancelledMessage + System.lineSeparator() + reassignedMessage;
            }
 
            PreparedStatement freeSeat = con.prepareStatement(
                    "UPDATE seats SET is_booked = false WHERE seat_id = ?");
            freeSeat.setInt(1, seatId);
            freeSeat.executeUpdate();
 
            con.commit();
            String message = "Booking for seat " + seatId + " was cancelled. Seat is now available.";
            System.out.println("\n[CANCELLED] Seat " + seatId + " is now free.");
            emitStatus(message);
 
            if (!waitingList.isEmpty()) {
                System.out.println("[INFO] Notifying remaining "
                        + waitingList.size() + " waiting user(s)...");
            }
            notifyAll();
            return message;
 
        } catch (Exception e) {
            String message = "[CANCEL] Error: " + e.getMessage();
            System.err.println(message);
            emitStatus(message);
            try {
                if (con != null) {
                    con.rollback();
                }
            } catch (Exception ex) {
                // ignore rollback failure
            }
            return message;
        } finally {
            try {
                if (con != null) {
                    con.setAutoCommit(true);
                }
            } catch (Exception ex) {
                // ignore reset failure
            }
        }
    }
 
    private String findTopWaiter(String category) {
        for (String entry : waitingList) {
            if (entry.split("\\|")[1].equals(category)) {
                return entry;
            }
        }
        return null;
    }
 
    public synchronized void showAvailableSeats() {
        try {
            List<String[]> rows = getAvailableSeatsData();
            System.out.println("\n--- Available Seats ---");
            if (rows.isEmpty()) {
                System.out.println("  (no seats available)");
                return;
            }
 
            for (String[] row : rows) {
                System.out.printf("  %-10s [%s]%n", row[1], row[2]);
            }
        } catch (Exception e) {
            System.err.println("[ERROR] showAvailableSeats: " + e.getMessage());
        }
    }
 
    public synchronized List<String[]> getAvailableSeatsData() {
        List<String[]> rows = new ArrayList<>();
        try {
            Connection con = DBConnection.getConnection();
            PreparedStatement ps = con.prepareStatement(
                    "SELECT seat_id, seat_number, category FROM seats " +
                    "WHERE is_booked = false ORDER BY category, seat_number");
            ResultSet rs = ps.executeQuery();
 
            while (rs.next()) {
                rows.add(new String[] {
                        String.valueOf(rs.getInt("seat_id")),
                        rs.getString("seat_number"),
                        rs.getString("category")
                });
            }
        } catch (Exception e) {
            rows.add(new String[] {"-", "Error loading seats", e.getMessage()});
        }
        return rows;
    }
 
    public synchronized void showReport() {
        try {
            List<String[]> rows = getReportData();
            System.out.println("\n--- Booking Report ---");
            if (rows.isEmpty()) {
                System.out.println("  (no confirmed bookings yet)");
                return;
            }
 
            for (String[] row : rows) {
                System.out.printf("  %-8s -> %s confirmed booking(s)%n", row[0], row[1]);
            }
        } catch (Exception e) {
            System.err.println("[ERROR] showReport: " + e.getMessage());
        }
    }
 
    public synchronized List<String[]> getReportData() {
        List<String[]> rows = new ArrayList<>();
        try {
            Connection con = DBConnection.getConnection();
            PreparedStatement ps = con.prepareStatement(
                    "SELECT category, COUNT(*) AS total " +
                    "FROM bookings WHERE status = 'CONFIRMED' GROUP BY category");
            ResultSet rs = ps.executeQuery();
 
            while (rs.next()) {
                rows.add(new String[] {
                        rs.getString("category"),
                        String.valueOf(rs.getInt("total"))
                });
            }
        } catch (Exception e) {
            rows.add(new String[] {"Error", e.getMessage()});
        }
        return rows;
    }
 
    public synchronized void showWaitingList() {
        printWaitingList();
    }
 
    public synchronized List<String[]> getWaitingListData() {
        List<String[]> rows = new ArrayList<>();
        int position = 1;
        for (String entry : waitingList) {
            String[] parts = entry.split("\\|");
            rows.add(new String[] {
                    String.valueOf(position++),
                    parts[0],
                    parts[1],
                    parts[2]
            });
        }
        return rows;
    }
 
    public synchronized List<String[]> getConfirmedBookingsData() {
        List<String[]> rows = new ArrayList<>();
        try {
            Connection con = DBConnection.getConnection();
            PreparedStatement ps = con.prepareStatement(
                    "SELECT b.seat_id, s.seat_number, b.user_name, b.category, b.status " +
                    "FROM bookings b JOIN seats s ON b.seat_id = s.seat_id " +
                    "WHERE b.status = 'CONFIRMED' ORDER BY b.seat_id");
            ResultSet rs = ps.executeQuery();
 
            while (rs.next()) {
                rows.add(new String[] {
                        String.valueOf(rs.getInt("seat_id")),
                        rs.getString("seat_number"),
                        rs.getString("user_name"),
                        rs.getString("category"),
                        rs.getString("status")
                });
            }
        } catch (Exception e) {
            rows.add(new String[] {"-", "-", "Error loading bookings", "-", e.getMessage()});
        }
        return rows;
    }
 
    private void printWaitingList() {
        System.out.println("\n--- Waiting List (" + waitingList.size() + ") ---");
        if (waitingList.isEmpty()) {
            System.out.println("  (empty)");
            return;
        }
 
        int pos = 1;
        for (String entry : waitingList) {
            String[] parts = entry.split("\\|");
            System.out.printf("  %d. %-15s [%s] (%s)%n", pos++, parts[0], parts[1], parts[2]);
        }
    }
}
