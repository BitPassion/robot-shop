package org.steveww.spark;

import com.mchange.v2.c3p0.ComboPooledDataSource;
import spark.Spark;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.handlers.MapListHandler;
import org.apache.commons.dbutils.DbUtils;
import com.google.gson.Gson;

import java.sql.Connection;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public class Main {
    private static Logger logger = LoggerFactory.getLogger(Main.class);
    private static ComboPooledDataSource cpds = null;

    public static void main(String[] args) {
        //
        // Create database connector
        // TODO - might need a retry loop here
        //
        try {
            cpds = new ComboPooledDataSource();
            cpds.setDriverClass( "com.mysql.jdbc.Driver" ); //loads the jdbc driver            
            cpds.setJdbcUrl( "jdbc:mysql://mysql/cities?useSSL=false&autoReconnect=true" );
            cpds.setUser("shipping");                                  
            cpds.setPassword("secret");
            // some config
            cpds.setMinPoolSize(5);
            cpds.setAcquireIncrement(5);
            cpds.setMaxPoolSize(20);
            cpds.setMaxStatements(180);
        }
        catch(Exception e) {
            logger.error("Database Exception", e);
        }

        // Spark
        Spark.port(8080);

        Spark.get("/health", (req, res) -> "OK");

        Spark.get("/count", (req, res) -> {
            String data;
            try {
                Connection conn = cpds.getConnection();
                data = queryToJson(conn, "select count(*) as count from cities");
                res.header("Content-Type", "application/json");
            } catch(Exception e) {
                logger.error("count", e);
                res.status(500);
                data = "ERROR";
            }

            return data;
        });

        Spark.get("/codes", (req, res) -> {
            String data;
            try {
                Connection conn = cpds.getConnection();
                String query = "select code, name from codes order by name asc";
                data = queryToJson(conn, query);
                res.header("Content-Type", "application/json");
            } catch(Exception e) {
                logger.error("codes", e);
                res.status(500);
                data = "ERROR";
            }

            return data;
        });

        Spark.get("/match/:code/:text", (req, res) -> {
            String data;
            try {
                Connection conn = cpds.getConnection();
                String query = "select uuid, name from cities where country_code ='" + req.params(":code") + "' and city like '" + req.params(":text") + "%' order by name asc limit 10";
                logger.info("Query " + query);
                data = queryToJson(conn, query);
                res.header("Content-Type", "application/json");
            } catch(Exception e) {
                logger.error("match", e);
                res.status(500);
                data = "ERROR";
            }

            return data;
        });

        Spark.get("/calc/:uuid", (req, res) -> {
            double homeLat = 51.164896;
            double homeLong = 7.068792;
            StringBuilder buffer = new StringBuilder();

            res.header("Content-Type", "application/json");
            buffer.append('{');
            Location location = getLocation(req.params(":uuid"));
            if(location != null) {
                // charge 0.05 Euro per km
                double distance = location.getDistance(homeLat, homeLong);
                double cost = distance * 0.05;
                buffer.append(write("distance", distance)).append(',');
                buffer.append(write("cost", cost));
            } else {
                res.status(500);
            }
            buffer.append('}');

            return buffer.toString();
        });

        Spark.post("/confirm", (req, res) -> {
            logger.info("confirm " + req.body());
            return "OK";
        });

        logger.info("Ready");
    }



    /**
     * Query to Json - QED
     **/
    private static String queryToJson(Connection connection, String query) {
        List<Map<String, Object>> listOfMaps = null;
        try {
            QueryRunner queryRunner = new QueryRunner();
            listOfMaps = queryRunner.query(connection, query, new MapListHandler());
        } catch (SQLException se) {
            throw new RuntimeException("Couldn't query the database.", se);
        } finally {
            DbUtils.closeQuietly(connection);
        }
        return new Gson().toJson(listOfMaps);
    }

    /**
     * Special case for location, dont want Json
     **/
    private static Location getLocation(String uuid) {
        Location location = null;
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        String query = "select latitude, longitude from cities where uuid = " + uuid;

        try {
            conn = cpds.getConnection();
            stmt = conn.createStatement();
            rs = stmt.executeQuery(query);
            while(rs.next()) {
                location = new Location(rs.getDouble(1), rs.getDouble(2));
                break;
            }
        } catch(Exception e) {
            logger.error("Query exception", e);
        } finally {
            DbUtils.closeQuietly(conn, stmt, rs);
        }

        return location;
    }

    private static String write(String key, Object val) {
        StringBuilder buffer = new StringBuilder();

        buffer.append('"').append(key).append('"').append(": ").append(val);

        return buffer.toString();
    }
}
