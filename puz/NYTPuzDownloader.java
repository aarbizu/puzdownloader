package puz;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CookieStore;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * Do basic login stuff and download for NYT crossword goodness
 * @author alan
 *
 */
public class NYTPuzDownloader {
	private static final String PROPSFILE = "nyt_xw.properties";
	private Properties properties;
	private boolean initialized = false;
	private DateFormat df = new SimpleDateFormat("MM-dd-yyyy");
	private DateFormat uriDateFormat = new SimpleDateFormat("yyyy-MM-dd");
	List<String> dates = Lists.newArrayListWithCapacity(31);
	
	enum FieldParamNames {
		IS_CONTINUE,
		TOKEN,
		EXPIRES,
		REMEMBER;
		
		String paramName() {
			return name().toLowerCase();
		}
	}
	
	NYTPuzDownloader() {
		properties = new Properties();
		try {
			properties.load(new FileReader(PROPSFILE));
			initialized = true;
		} catch (Exception e) {
			throw new RuntimeException("Error reading properties file " + PROPSFILE, e);
		} 
	}
	
	/**
	 * Assumes format is MM-dd-yyyy. e.g. 01 Jun 2014 == "06-01-2014", then adds
	 * it to list of dates for which to download puzzles in the format the NYT
	 * stores its .puz files in, namely, "yyyy-MM-dd"
	 */
	public void addDate(String dateString) {
		Date puzDate;
		try {
			puzDate = df.parse(dateString);
			dates.add(uriDateFormat.format(puzDate));
		} catch (ParseException e) {
			System.err.println("Problem parsing date: " + dateString);
		}
	}
	
	public String getUserAgent() {
		return properties.getProperty("user_agent");
	}
	
	private String getPuzUriTemplate() {
		return properties.getProperty("puz_uri_tmpl");
	}
	
	private String getPassword() {
		return properties.getProperty("password");
	}
	
	private String getUsername() {
		return properties.getProperty("username");
	}
	
	private String getLoginUri() {
		return properties.getProperty("loginuri");
	}
	
	public void doDownloads() {
		CloseableHttpClient c = login();
		try {
			for (String date : dates) {
				System.out.print(date);
				doDownload(c, date);
				System.out.println("OK!");
			}
		} catch (ClientProtocolException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (c != null) {
				try {
					c.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	
	/**
	 * Login using properties file credentials, saving the cookies in the HttpClient
	 * @return
	 */
	public CloseableHttpClient login() {
		CloseableHttpClient client = null;
		CloseableHttpResponse res = null;
		try {
			CookieStore cookies = new BasicCookieStore();
			client = HttpClients.custom()
					.setUserAgent(getUserAgent())
					.setDefaultCookieStore(cookies)
					.build();
			
			// load the login page to get form values
			HttpGet get = new HttpGet(getLoginUri());
			res = client.execute(get);
			String html = EntityUtils.toString(res.getEntity());
			res.close(); res = null; // re-use this for the next request
			// get the form fields we need for subsequent requests
			Document doc = Jsoup.parse(html);
			Elements elems = doc.select("form input"); //CSS query, roughly "get FORM tags of type INPUT"
			List<NameValuePair> loginFormData = Lists.newArrayList();
			Set<String> formFields = Sets.newHashSetWithExpectedSize(FieldParamNames.values().length);
			for (FieldParamNames p : FieldParamNames.values()) {
				formFields.add(p.paramName());
			}
			
			// need: is_continue, token, expires, remember. we'll add user/pass afterwards
			for (Element e : elems) {
				String param = e.attr("name");
				if (formFields.contains(param)) {
					loginFormData.add(new BasicNameValuePair(param, e.attr("value")));
				}
			}
			
			// now add credentials
			loginFormData.add(new BasicNameValuePair("userid", getUsername()));
			loginFormData.add(new BasicNameValuePair("password", getPassword()));
			
			HttpPost login = new HttpPost(getLoginUri());
			login.setEntity(new UrlEncodedFormEntity(loginFormData));
			res = client.execute(login);
		} catch (ClientProtocolException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				if (res != null) res.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return client;
	}
	
	public void doDownload(CloseableHttpClient client, String dateString) throws ClientProtocolException, IOException {
		HttpGet puzFileGet = new HttpGet(getPuzUriTemplate() + "daily-" + dateString + ".puz");
		CloseableHttpResponse res = client.execute(puzFileGet);
		
		BufferedInputStream bis = new BufferedInputStream(res.getEntity().getContent());
		String puzPath = dateString + ".puz";
		BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(new File(puzPath)));
		int inByte;
		int i = 0;
		while ((inByte = bis.read()) != -1) {
			bos.write(inByte);
			if (++i % 1000 == 0) System.out.print(".");
		}
		bis.close();
		bos.close();
	}

	// Initialize and get dates to download puzzles for from command line args 
	
	public static void main(String... args) {
		NYTPuzDownloader dl = new NYTPuzDownloader();
		
		if (args.length == 0) {
			dl.initialized = false;
		} else {
			for (String s : args) {
				dl.addDate(s);
			}
			System.out.println("Downloading " + dl.dates.size() + " puzzles in .PUZ format");
		}
		
		if (!dl.initialized) {
			System.err.println("Not initialized correctly");
		} else {
			dl.doDownloads();
			System.out.println("Done.");
		}
	}
}
