import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DBConnection {

    private static final String URL  = "jdbc:mysql://localhost:3306/bookify?useSSL=false&serverTimezone=UTC";
    private static final String USER = "root";
    private static final String PASS = "root123";

    
    private static Connection connection;

    public static synchronized Connection getConnection() throws SQLException {
        try {
            if (connection == null || connection.isClosed()) {
                connection = DriverManager.getConnection(URL, USER, PASS);
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new SQLException("Could not connect to database: " + e.getMessage(), e);
        }
        return connection;
    }
}
