import org.apache.pig.EvalFunc;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.data.BagFactory;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.impl.logicalLayer.schema.Schema;
import org.apache.pig.impl.util.UDFContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * EvalFunc to take the given set of values and convert them
 * into a CassandraBag for persisting back to Cassandra.
 *
 * The first value in the input bag *has* to be the key. For
 * the rest of the fields, this UDF will interrogate the values
 * that you have named the variables to be the column names.
 * If you have bags of (name, value) tuples as elements of the
 * input bag, it will just add all of their tuples to the bag to
 * be persisted individually.
 */
public class ToCassandraBag extends EvalFunc<Tuple> {
    private transient static Logger log = LoggerFactory.getLogger(ToCassandraBag.class);
    public static final String UDFCONTEXT_SCHEMA_KEY = "cassandra.input_field_schema";
    private static final Pattern INPUT_DELIM = Pattern.compile("[\\s,]+");
    private static final char OUTPUT_DELIM = ',';
    private static final String defaultContext = "default_context";
    private String context;

    public ToCassandraBag() {
        this(defaultContext);
    }

    /**
     * Pass in a unique value for the script for the context, e.g. a relation name.
     * @param context
     */
    public ToCassandraBag(String context) {
        this.context = context;
    }

    public Tuple exec(Tuple input) throws IOException {
        Tuple row = TupleFactory.getInstance().newTuple(2);
        DataBag columns = BagFactory.getInstance().newDefaultBag();
        UDFContext context = UDFContext.getUDFContext();
        Properties property = context.getUDFProperties(ToCassandraBag.class);
        String fieldString = property.getProperty(getSchemaKey());
        String [] fieldnames = INPUT_DELIM.split(fieldString);
        if (log.isDebugEnabled()) {
            log.debug("Tuple: " + input.toDelimitedString(",") + " Fields: " + fieldString);
        }

        // IT IS ALWAYS ASSUMED THAT THE OBJECT AT INDEX 0 IS THE ROW KEY
        if(input.get(0)==null)
            throw new IOException("The object at index 0 is the row key, its value can't be null!");
        if (input.size() != fieldnames.length){
            throw new IOException("There is a mismatch between the number of inputs (" + input.size() + " and fieldnames (" + fieldnames.length + ")");
        }
        for (int i=1; i<input.size(); i++) {
            if (input.get(i) instanceof DataBag) {
                columns.addAll((DataBag) input.get(i));
            } else {
                columns.add(getColumnDef(fieldnames[i], input.get(i)));
            }
        }

        row.set(0, input.get(0));
        row.set(1, columns);
        return row;
    }

    private Tuple getColumnDef(String name, Object value) throws ExecException {
        Tuple column = TupleFactory.getInstance().newTuple(2);
        column.set(0, name);
        column.set(1, value);
        return column;
    }

    public Schema outputSchema(Schema input) {
        StringBuilder builder = new StringBuilder();
        List<Schema.FieldSchema> fields = input.getFields();
        for (int i=0; i<fields.size(); i++) {
            builder.append(fields.get(i).alias);
            if (i != fields.size()-1) {
                builder.append(OUTPUT_DELIM);
            }
        }
        
        UDFContext context = UDFContext.getUDFContext();
        Properties property = context.getUDFProperties(ToCassandraBag.class);
        property.setProperty(getSchemaKey(), builder.toString());

        return super.outputSchema(input);
    }

    private String getSchemaKey() {
        return UDFCONTEXT_SCHEMA_KEY + '.' + context;
    }
}
