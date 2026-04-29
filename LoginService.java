import java.sql.*;
import java.security.MessageDigest;

public class LoginService {

    public static class LoginResult {
        private final boolean success;
        private final String message;

        public LoginResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    }

    public boolean login(String username, String plainPassword) {
        return attemptLogin(username, plainPassword).isSuccess();
    }

    public LoginResult attemptLogin(String username, String plainPassword) {
        if (username == null || plainPassword == null
                || username.isBlank() || plainPassword.isBlank()) {
            return new LoginResult(false, "Username and password are required.");
        }

        try {
            Connection con = DBConnection.getConnection();

            PreparedStatement ps = con.prepareStatement(
                    "SELECT password FROM users WHERE username = ?");
            ps.setString(1, username);

            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                String storedPassword = rs.getString("password");
                String hashedInput = hashPassword(plainPassword);
                boolean matched = plainPassword.equals(storedPassword) || hashedInput.equals(storedPassword);
                if (matched) {
                    return new LoginResult(true, "Login successful.");
                }
                return new LoginResult(false, "Password did not match the value stored in the users table.");
            }

            return new LoginResult(false, "Username was not found in the users table.");

        } catch (Exception e) {
            String message = "Database error during login: " + e.getMessage();
            System.err.println("[LoginService] " + message);
            return new LoginResult(false, message);
        }
    }

    static String hashPassword(String plain) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(plain.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Hashing failed", e);
        }
    }
}
