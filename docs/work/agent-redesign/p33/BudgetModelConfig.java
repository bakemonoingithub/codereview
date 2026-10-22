import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Private pipe helper for the explicitly invoked experiment. Stdout is consumed in memory, never logged. */
class BudgetModelConfig {
    static String expand(String input) {
        Matcher m=Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}").matcher(input);StringBuilder out=new StringBuilder();
        while(m.find()){String value=System.getenv(m.group(1));if(value==null)value=m.group(2);if(value==null)throw new IllegalArgumentException();m.appendReplacement(out,Matcher.quoteReplacement(value));}m.appendTail(out);return out.toString();
    }
    @SuppressWarnings("unchecked") public static void main(String[] args) {
        try {
            Map<String,Object> config=new Yaml().load(Files.readString(Path.of(args[0],"backend/src/main/resources/application.yml")));
            Map<String,Object> db=(Map<String,Object>)((Map<String,Object>)config.get("agent")).get("datasource");
            Properties p=new Properties();p.setProperty("user",expand(db.get("username").toString()));p.setProperty("password",expand(db.get("password").toString()));p.setProperty("connectTimeout","8000");p.setProperty("socketTimeout","15000");
            try(Connection c=DriverManager.getConnection(expand(db.get("url").toString()),p)) {
                if(!"ai_code_review_agent".equals(c.getCatalog()))throw new IllegalStateException();c.setReadOnly(true);
                try(PreparedStatement q=c.prepareStatement("SELECT id, model_name, base_url, credential, revision, context_window, max_output FROM model_config WHERE is_deleted=0 AND model_name='deepseek-flash'");ResultSet r=q.executeQuery()) {
                    if(!r.next())throw new IllegalStateException();Map<String,Object> out=new LinkedHashMap<>();
                    for(String k:List.of("id","model_name","base_url","credential","revision","context_window","max_output"))out.put(k,r.getObject(k));
                    if(r.next())throw new IllegalStateException();System.out.print(new ObjectMapper().writeValueAsString(out));
                }
            }
        }catch(Exception e){System.err.print("Model configuration helper failed: "+e.getClass().getSimpleName());System.exit(1);}
    }
}
