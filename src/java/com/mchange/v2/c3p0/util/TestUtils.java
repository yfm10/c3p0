/*
 * Distributed as part of c3p0 v.0.9.5-pre6
 *
 * Copyright (C) 2013 Machinery For Change, Inc.
 *
 * Author: Steve Waldman <swaldman@mchange.com>
 *
 * This library is free software; you can redistribute it and/or modify
 * it under the terms of EITHER:
 *
 *     1) The GNU Lesser General Public License (LGPL), version 2.1, as 
 *        published by the Free Software Foundation
 *
 * OR
 *
 *     2) The Eclipse Public License (EPL), version 1.0
 *
 * You may choose which license to accept if you wish to redistribute
 * or modify this work. You may offer derivatives of this work
 * under the license you have chosen, or you may provide the same
 * choice of license which you have been offered here.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received copies of both LGPL v2.1 and EPL v1.0
 * along with this software; see the files LICENSE-EPL and LICENSE-LGPL.
 * If not, the text of these licenses are currently available at
 *
 * LGPL v2.1: http://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
 *  EPL v1.0: http://www.eclipse.org/org/documents/epl-v10.php 
 * 
 */

package com.mchange.v2.c3p0.util;

import java.sql.*;
import javax.sql.*;
import java.lang.reflect.*;
import java.util.Random;
import com.mchange.v2.sql.SqlUtils;
import com.mchange.v2.c3p0.C3P0ProxyConnection;

public final class TestUtils
{
    private final static Method OBJECT_EQUALS;
    private final static Method IDENTITY_HASHCODE;
    private final static Method IPCFP;


    static
    {
	try
	    { 
		OBJECT_EQUALS = Object.class.getMethod("equals", new Class[] { Object.class }); 
		IDENTITY_HASHCODE = System.class.getMethod("identityHashCode", new Class[] {Object.class});

		// is this okay? getting a method from a class while it is still being initialized?
		IPCFP = TestUtils.class.getMethod("isPhysicalConnectionForProxy", new Class[] {Connection.class, C3P0ProxyConnection.class});
	    }
	catch ( Exception e )
	    {
		e.printStackTrace();
		throw new RuntimeException("Huh? Can't reflectively get ahold of expected methods?");
	    }
    }
		
    /**
     * In general, if this method returns true for two distinct C3P0ProxyConnections, it indicates a c3p0 bug.
     * Once a proxy Connection is close()ed, it should not permit any sort of operation. Prior to Connection
     * close(), there should be at most one valid proxy Connection associated with a given physical Connection.
     */
    public static boolean samePhysicalConnection( C3P0ProxyConnection con1, C3P0ProxyConnection con2 ) throws SQLException
    {
	try 
	    { 
		Object out = con1.rawConnectionOperation( IPCFP, null, new Object[] { C3P0ProxyConnection.RAW_CONNECTION, con2 } ); 
		return ((Boolean) out).booleanValue();
	    }
	catch (Exception e)
	    {
		e.printStackTrace();
		throw SqlUtils.toSQLException( e );
	    }
    }

    public static boolean isPhysicalConnectionForProxy( Connection physicalConnection, C3P0ProxyConnection proxy ) throws SQLException
    {
	try 
	    { 
		Object out = proxy.rawConnectionOperation( OBJECT_EQUALS, physicalConnection, new Object[] { C3P0ProxyConnection.RAW_CONNECTION } ); 
		return ((Boolean) out).booleanValue();
	    }
	catch (Exception e)
	    {
		e.printStackTrace();
		throw SqlUtils.toSQLException( e );
	    }
    }

    public static int physicalConnectionIdentityHashCode( C3P0ProxyConnection conn ) throws SQLException
    {
	try 
	    { 
		Object out = conn.rawConnectionOperation( IDENTITY_HASHCODE, null, new Object[] { C3P0ProxyConnection.RAW_CONNECTION } ); 
		return ((Integer) out).intValue();
	    }
	catch (Exception e)
	    {
		e.printStackTrace();
		throw SqlUtils.toSQLException( e );
	    }
    }


    public static DataSource unreliableCommitDataSource(DataSource ds) throws Exception
    {
	return (DataSource) Proxy.newProxyInstance( TestUtils.class.getClassLoader(),
						    new Class[] { DataSource.class },
						    new StupidDataSourceInvocationHandler( ds ) );
    }

    private TestUtils()
    {}

    static class StupidDataSourceInvocationHandler implements InvocationHandler
    {
	DataSource ds;
	Random r = new Random();
	
	StupidDataSourceInvocationHandler(DataSource ds)
	{ this.ds = ds; }
	
	public Object invoke(Object proxy, Method method, Object[] args)
	    throws Throwable
	{
	    if ( "getConnection".equals(method.getName()) )
		{
		    Connection conn = (Connection) method.invoke( ds, args );
		    return Proxy.newProxyInstance( TestUtils.class.getClassLoader(),
						   new Class[] { Connection.class },
						   new StupidConnectionInvocationHandler( conn ) );
		}
	    else
		return method.invoke( ds, args );
	}
    }

    static class StupidConnectionInvocationHandler implements InvocationHandler
    {
	Connection conn;
	Random r = new Random();
	
	boolean invalid = false;

	StupidConnectionInvocationHandler(Connection conn)
	{ this.conn = conn; }
	
	public Object invoke(Object proxy, Method method, Object[] args)
	    throws Throwable
	{
	    if ("close".equals(method.getName()))
		{
		    if (invalid)
			{
			    new Exception("Duplicate close() called on Connection!!!").printStackTrace();
			}
		    else
			{
			    //new Exception("CLOSE CALLED ON UNPOOLED DATASOURCE CONNECTION!").printStackTrace();
			    invalid = true;
			}
		    return null;
		}
	    else if ( invalid )
		throw new SQLException("Connection closed -- cannot " + method.getName());
	    else if ( "commit".equals(method.getName())  && r.nextInt(100) == 0 )
		{
		    conn.rollback();
		    throw new SQLException("Random commit exception!!!");
		}
	    else if (r.nextInt(200) == 0)
		{
		    conn.rollback();
		    conn.close();
		    throw new SQLException("Random Fatal Exception Occurred!!!");
		}
	    else
		return method.invoke( conn, args );
	}
    }

}
