package servlets;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import util.AsyncCompletion;

public class httpsend extends HttpServlet {
    /**
     * 
     */
    private static final long serialVersionUID = 1L;
    
    private AsyncCompletion acomp = null;
    private String mwc713Script = null;
    private static String mwc713DecryptScript = null;
    
    private static Map<String,AsyncResponseHolder> responses
             = new HashMap<String,AsyncResponseHolder>();
    private static Object responseLock = new Object();
    
    private class AsyncResponseHolder
    {
        AsyncContext ac = null;
        PrintWriter pw = null;
    }
    
    public static void addMessage(String address, String message)
    {
        AsyncResponseHolder arh = null;
        BufferedReader buf = null;
        String line;
        String tx_id = null;

        log.error("message="+message);

        
        ProcessBuilder pb = new ProcessBuilder(
                mwc713DecryptScript,
                "'" + message + "'");

        String slate = null;

        try
        {
            Process proc = pb.start();
            buf = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()));


            while((line=buf.readLine()) != null)
            {
                log.error("decryptline="+line);
                if(line.startsWith("slate='"))
                {
                    slate = line.substring(7);
                    slate = slate.substring(0, slate.indexOf('\''));
                }
            }
            
            System.out.println("Slate="+slate);
            
            JSONObject obj = new JSONObject(slate);
            tx_id = obj.getString("id");
        }
        catch(Exception err)
        {
            log.error("exception encrypting slate", err);
        }
        finally
        {
            try { buf.close(); } catch(Exception ign) {}
        }

        
        synchronized(responseLock)
        {
            arh = responses.get(tx_id);
        }
        
        if(arh == null)
        {
            log.error("Couldn't find an object for address: " + tx_id);
            return;
        }
        arh.pw.println("{\"id\":1,\"jsonrpc\":\"2.0\",\"result\":{\"Ok\": " + slate + "}");
        arh.pw.flush();
        arh.ac.complete();
    }
    
    private static Logger log = LoggerFactory.getLogger(httpsend.class);
    
    public void init()
    {
        acomp = listener.acomp;
        mwc713Script = listener.mwc713Script;
        mwc713DecryptScript = listener.mwc713DecryptScript;
    }

    public void doPost(HttpServletRequest req, HttpServletResponse res)
    {
        String domain = sender.domain;
        String address = req.getParameter("address");

        if(address != null)
        {
            int end = address.indexOf('/');
            if(end>=0)
                address = address.substring(0, end);
        }

        String address_pre = address;

        
        if(!sender.DEFAULT_MWCMQS_DOMAIN.equals(domain))
            address += "@" + domain;


        PrintWriter pw = null;
        BufferedReader buf = null, buf2 = null;
        try {
            buf = new BufferedReader(new InputStreamReader(req.getInputStream()));
            String line;
            log.error("address="+address);

            pw = new PrintWriter(res.getOutputStream());

            if(address_pre != null && address_pre.contains("@"))
            {
                pw.println("{\n" + 
                        "  \"id\": 1,\n" + 
                        "  \"jsonrpc\": \"2.0\",\n" + 
                        "  \"result\": {\n" + 
                        "    \"Err\": {\n" + 
                        "      \"AddressFormatError\": \"Only local addresses supported.\"\n" + 
                        "    }\n" + 
                        "  }\n" +
                        "}");
                pw.flush();
                return;
            }
            
            StringBuilder sb = new StringBuilder();
            while((line=buf.readLine()) != null)
                sb.append(line);

            JSONObject obj = new JSONObject(sb.toString());
            if("check_version".equalsIgnoreCase(obj.getString("method")))
            {
                log.error("got a checkversion");
                pw.println("{\n" + 
                        "  \"id\": 1,\n" + 
                        "  \"jsonrpc\": \"2.0\",\n" + 
                        "  \"result\": {\n" + 
                        "    \"Ok\": {\n" + 
                        "      \"foreign_api_version\": 2,\n" + 
                        "      \"supported_slate_versions\": [\n" + 
                        "        \"V3\",\n" + 
                        "        \"V2\"\n" + 
                        "      ]\n" + 
                        "    }\n" + 
                        "  }\n" +
                        "}");
                pw.flush();
            } else {
                // we pass to another thread so start async
                AsyncContext ac = req.startAsync();
                ac.setTimeout(100*1000); // 100 second timeout
                
                // get encrypted slate

                obj = new JSONObject(sb.toString());
                JSONArray params = obj.getJSONArray("params");
                JSONObject slateObj = params.getJSONObject(0);
                String slate = slateObj.toString();
                String tx_id = slateObj.getString("id");

                log.error("slate="+slate);
                log.error("address_pre="+address_pre);
                
                ProcessBuilder pb = new ProcessBuilder(
                        mwc713Script,
                        "'" + slate + "'",
                        address_pre);

                Process proc = pb.start();
                buf2 = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()));

                
                String encrypted_slate = null;
                while((line=buf2.readLine()) != null)
                {
                    log.error("line="+line);
                    if(line.startsWith("slate="))
                    {
                        encrypted_slate = line.substring(line.indexOf('\'')+1);
                        encrypted_slate = encrypted_slate.substring(0,
                                              encrypted_slate.indexOf('\''));
                        break;
                    }
                }
                
                AsyncResponseHolder arh = new AsyncResponseHolder();
                arh.ac = ac;
                arh.pw = pw;
                
                log.error("Putting object for: " + address);

                synchronized(responseLock)
                {
                    responses.put(tx_id, arh);
                }
                
                acomp.send(address, encrypted_slate);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        finally {
            try { buf.close(); } catch(Exception ign) {}
            try { buf2.close(); } catch(Exception ign) {}
        }
    }
}
