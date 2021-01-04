package com.hegx.common;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.hegx.entity.TableContent;
import com.hegx.models.DBType;
import com.hegx.models.TableAttributeEntity;
import com.hegx.options.DatabaseConfig;

/**
 * 数据库工具
 * 
 * @author hegx
 *
 */
public class DBUtil {
	private static Logger LOG = Logger.getLogger(DBUtil.class);
	private static final int DB_CONNECTION_TIMEOUTS_SECONDS = 1;

	/**
	 * 获得数据库连接
	 * 
	 * @param config
	 * @return
	 * @throws ClassNotFoundException
	 * @throws SQLException
	 */
	public static Connection getConnection(DatabaseConfig config) throws ClassNotFoundException, SQLException {
		DriverManager.setLoginTimeout(DB_CONNECTION_TIMEOUTS_SECONDS);
		DBType dbType = DBType.valueOf(config.getDbType());
		Class.forName(dbType.getDriverClass());
		String url = getConnectionURL(config);
		if (dbType == DBType.Oracle) {
			Connection connection;
			try {
				connection = DriverManager.getConnection(url, config.getUserName(), config.getUserPwd());
			} catch (Exception e) {
				String oracle = String.format(DBType.OracleServiceName.getConnectionUrlPattern(), config.getConnURL(), config.getListenPort(),
						config.getDbName());
				connection = DriverManager.getConnection(oracle, config.getUserName(), config.getUserPwd());
			}
			return connection;
		} else {
			return DriverManager.getConnection(url, config.getUserName(), config.getUserPwd());
		}
	}

	/**
	 * 获得数据库连接URL
	 * 
	 * @param dbConfig
	 * @return
	 * @throws ClassNotFoundException
	 */
	public static String getConnectionURL(DatabaseConfig dbConfig) throws ClassNotFoundException {
		DBType dbType = DBType.valueOf(dbConfig.getDbType());
		String connectionRUL = String.format(dbType.getConnectionUrlPattern(), dbConfig.getConnURL(), dbConfig.getListenPort(),
				dbConfig.getDbName(), dbConfig.getEncoding());
		return connectionRUL;
	}

	/**
	 * 获得数据库的表名
	 * 
	 * @param config
	 * @return
	 * @throws Exception
	 */
	public static List<String> getTableNames(DatabaseConfig config) throws Exception {
		Connection conn = getConnection(config);
		List<String> tables = new ArrayList<>();
		ResultSet rs;
		if (config.getDbType().equalsIgnoreCase(Constant.SQL_SERVER)) {
			// 如果是sqlserver数据库通过查询获得所有表跟视图
			String sql = "select name from sysobjects where UPPER(xtype)='U' or UPPER(xtype)='V'";
			rs = conn.createStatement().executeQuery(sql);
			while (rs.next()) {
				tables.add(rs.getString("name"));
			}

		} else {
			// 如果非sqlserver类型的数据库通过JDBC获得所有表跟视图
			DatabaseMetaData md = conn.getMetaData();
			String[] types = {"TABLE", "VIEW"};
			if (config.getDbType().equalsIgnoreCase(Constant.POSTGRE_SQL)) {
				rs = md.getTables(null, null, null, types);
			} else {
				String catalog = conn.getCatalog() == null ? null : conn.getCatalog();
				rs = md.getTables(catalog, config.getUserName().toUpperCase(), "%%", types);
			}
			while (rs.next()) {
				tables.add(rs.getString(3));
			}
		}
		return tables;
	}

	/**
	 * 获取表备注
	 */
	private static void getTableComment(Connection connection, TableContent content) {
		try{
			PreparedStatement preparedStatement2 = connection.prepareStatement("show create table "+content.getTableName()+"");
			ResultSet resultSet2 = preparedStatement2.executeQuery();
			while(resultSet2.next()) {
				String createTable = resultSet2.getString("Create Table");
				try {
					String tableComment = createTable.split("COMMENT=")[1].split("'")[1];
					content.setTableComment(tableComment);
				} catch (IndexOutOfBoundsException e) {
					LOG.info("获取表备注异常");
				}
			}
		} catch (Exception e) {
			LOG.error("method2 error ", e);
		}
	}

	/**
	 * 获得指定表的属性
	 * 
	 * @param config
	 * @param tableName
	 * @return
	 * @throws Exception
	 */
	public static TableContent getTableAttribute(DatabaseConfig config, String tableName) throws Exception {
		Connection conn = getConnection(config);
		TableContent content = new TableContent();
		ResultSet rs;
		DatabaseMetaData md = conn.getMetaData();
		String[] types = {"TABLE", "VIEW"};
		if (config.getDbType().equalsIgnoreCase(Constant.POSTGRE_SQL)) {
			rs = md.getTables(null, null, tableName, types);
		} else {
			String catalog = conn.getCatalog() == null ? null : conn.getCatalog();
			rs = md.getTables(catalog, config.getUserName().toUpperCase(), tableName, types);
		}
		if (rs.next()) {
			try {
				content.setTableName(tableName);
				content.setTableType(rs.getString("TABLE_TYPE"));
				content.setRemarks(rs.getString("REMARKS"));
				//补充表备注
				getTableComment(conn,content);
				try {
					content.setTableCat(rs.getString("TABLE_CAT"));
					content.setTableSchem(rs.getString("TABLE_SCHEM"));
					content.setTypeCat(rs.getString("TYPE_CAT"));
					content.setTypeSchem(rs.getString("TYPE_SCHEM"));
					content.setTypeName(rs.getString("TYPE_NAME"));
					content.setSelfReferencingColName(rs.getString("SELF_REFERENCING_COL_NAME"));
					content.setRefGeneration(rs.getString("REF_GENERATION"));
				} catch (Exception e) {
					LOG.debug("获取表属性一些可能为null的字段失败", e);
				}
			} catch (Exception e) {
				LOG.error("获取部分表属性失败:", e);
			}
		}
		return content;
	}

	/**
	 * 获取表的列属性
	 * 
	 * @param config
	 *          数据库配置文件
	 * @param tableName
	 *          表名
	 * @return
	 * @throws Exception
	 */
	public static List<TableAttributeEntity> getTableColumns(DatabaseConfig config, String tableName) throws Exception {
		Connection conn = getConnection(config);
		DatabaseMetaData md = conn.getMetaData();

		ResultSet rs = null;
		if (config.getDbType().equalsIgnoreCase(Constant.MYSQL)) {
			rs = md.getColumns(conn.getCatalog(), "%%", tableName, "%%");
		} else {
			rs = md.getColumns(null, null, tableName, null);
		}

		Map<String, TableAttributeEntity> columnMap = new HashMap<>();
		while (rs.next()) {
			try {
				TableAttributeEntity attr = new TableAttributeEntity();
				attr.setTdColumnName(rs.getString("COLUMN_NAME"));
				attr.setTdJdbcType(rs.getString("TYPE_NAME"));
				attr.setTdJavaType(JavaType.jdbcTypeToJavaType(rs.getString("TYPE_NAME")));

				attr.setColumnDef(rs.getString("COLUMN_DEF"));
				attr.setRemarks(rs.getString("REMARKS"));
				attr.setColumnSize(rs.getInt("COLUMN_SIZE"));
				attr.setDecimalDigits(rs.getInt("DECIMAL_DIGITS"));
				attr.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));
				attr.setNullable(rs.getInt("NULLABLE") == 1 ? true : false);
				columnMap.put(rs.getString("COLUMN_NAME"), attr);
			} catch (Exception e) {
				LOG.error("获取部分表属性失败:", e);
			}
		}
		if (columnMap.size() == 0) {
			throw new NullPointerException("从表中获取字段失败!获取不到任何字段!");
		}
		ArrayList<TableAttributeEntity> result = new ArrayList<>(columnMap.values());
		Collections.sort(result);
		return result;
	}

	/**
	 * 获得主键名称
	 * 
	 * @param config
	 * @param tableName
	 * @return
	 * @throws Exception
	 */
	public static String getTablePrimaryKey(DatabaseConfig config, String tableName) throws Exception {
		Connection conn = getConnection(config);
		DatabaseMetaData md = conn.getMetaData();
		ResultSet rs = null;
		if (config.getDbType().equalsIgnoreCase(Constant.MYSQL)) {
			rs = md.getPrimaryKeys(conn.getCatalog(), conn.getSchema(), tableName);
		} else {
			rs = md.getPrimaryKeys(null, null, tableName);
		}
		while (rs.next()) {
			return rs.getString("COLUMN_NAME");
		}
		return null;
	}

}
