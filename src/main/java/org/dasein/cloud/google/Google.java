/**
 * Copyright (C) 2012-2013 Dell, Inc
 * See annotations for authorship information
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */

package org.dasein.cloud.google;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import org.apache.log4j.Logger;
import org.dasein.cloud.AbstractCloud;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.google.compute.GoogleCompute;
import org.dasein.cloud.google.network.GoogleNetwork;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.text.ParseException;

/**
 * Support for the Google API through Dasein Cloud.
 * <p>Created by George Reese: 12/06/2012 9:35 AM</p>
 * @author George Reese
 * @version 2013.01 initial version
 * @since 2013.01
 */
public class Google extends AbstractCloud {
	static private final Logger logger = getLogger(Google.class);

	public final static String ISO8601_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";
	public final static String ISO8601_NO_MS_PATTERN = "yyyy-MM-dd'T'HH:mm:ss'Z'";
	
	static private @Nonnull String getLastItem(@Nonnull String name) {
		int idx = name.lastIndexOf('.');

		if( idx < 0 ) {
			return name;
		}
		else if( idx == (name.length()-1) ) {
			return "";
		}
		return name.substring(idx + 1);
	}

	static public @Nonnull Logger getLogger(@Nonnull Class<?> cls) {
		String pkg = getLastItem(cls.getPackage().getName());

		if( pkg.equals("google") ) {
			pkg = "";
		}
		else {
			pkg = pkg + ".";
		}
		return Logger.getLogger("dasein.cloud.google.std." + pkg + getLastItem(cls.getName()));
	}

	static public @Nonnull Logger getWireLogger(@Nonnull Class<?> cls) {
		return Logger.getLogger("dasein.cloud.google.wire." + getLastItem(cls.getPackage().getName()) + "." + getLastItem(cls.getName()));
	}

	public Google() { }

	@Override
	public @Nonnull String getCloudName() {
		ProviderContext ctx = getContext();
		String name = (ctx == null ? null : ctx.getCloudName());

		return (name == null ? "Google" : name);
	}

	@Override
	public @Nonnull DataCenters getDataCenterServices() {
		return new DataCenters(this);
	}

	@Override
	public GoogleCompute getComputeServices() {
		return new GoogleCompute(this);
	}

		@Override
		public GoogleNetwork getNetworkServices() {
			return new GoogleNetwork(this);
		}

	@Override
	public @Nonnull String getProviderName() {
		ProviderContext ctx = getContext();
		String name = (ctx == null ? null : ctx.getProviderName());

		return (name == null ? "Google" : name);
	}

	@Override
	public @Nullable String testContext() {
		if( logger.isTraceEnabled() ) {
			logger.trace("ENTER - " + Google.class.getName() + ".testContext()");
		}
		try {
			ProviderContext ctx = getContext();

			if( ctx == null ) {
				logger.warn("No context was provided for testing");
				return null;
			}
			try {
				// TODO: Go to Google and verify that the specified credentials in the context are correct
				// return null if they are not
				// return an account number if they are
				return null;
			}
			catch( Throwable t ) {
				logger.error("Error querying API key: " + t.getMessage());
				t.printStackTrace();
				return null;
			}
		}
		finally {
			if( logger.isTraceEnabled() ) {
				logger.trace("EXIT - " + Google.class.getName() + ".textContext()");
			}
		}
	}
}