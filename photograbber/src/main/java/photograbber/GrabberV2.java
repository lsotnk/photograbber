package photograbber;

import static com.google.common.collect.Lists.newArrayList;
import static java.util.stream.Collectors.joining;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.htmlparser.util.ParserException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;

public class GrabberV2 {

    private static final Logger LOG = LoggerFactory.getLogger(GrabberV2.class);

    public static void main(String[] args) throws Exception {
        GrabberV2 grabber = new GrabberV2("", "", 60);
        try {
            grabber.run();
        } finally {
            grabber.dispose();
        }

    }

    private String url;
    private String outputDir;
    private HttpClient httpclient;
    private int maxDepth;

    public GrabberV2(String url, String outputFolder, int maxDepth) {
        super();
        this.url = url;
        this.outputDir = outputFolder;
        this.httpclient = HttpClientBuilder.create().build();
        this.maxDepth = maxDepth;
    }

    public void run() throws Exception {
        LOG.info("Running grabber with url='{}', outputDir='{}', maxDepth='{}'", this.url, this.outputDir, this.maxDepth);
        PageParams params = null;
        for (int i = 0; i < this.maxDepth; i++) {
            LOG.info("================ PAGE " + (i + 1) + " ================");
            String content = fetchContent(params == null ? null : params.nextParams);
            params = extractURLs(content);
            LOG.info("Found " + params.photos.size() + " groups");
            LOG.info("Next params: " + params.nextParams);
            downloadPhotos(params.photos);
        }
    }

    public void dispose() {
        if (this.httpclient instanceof Closeable) {
            IOUtils.closeQuietly((Closeable) this.httpclient);
        }
    }

    private void downloadPhotos(List<Group> groups) throws ClientProtocolException, IOException {
        for (Group group : groups) {
            if (group.urls.size() == 0) {
                LOG.info("GroupTest " + group.date + " is empty");
                continue;
            }
            if (group.date == null) {
                LOG.error("Date is null");
                continue;
            }
            String base = group.name + " - " + group.date;
            if (createCacheEntry(base)) {
                LOG.info("Processing group " + base + " with " + group.urls.size() + " photos");
                LOG.info("  Cache entry " + group.date + " created");
                for (String url : group.urls) {
                    LOG.info("  Downloading " + url + "...");
                    HttpResponse response = this.httpclient.execute(new HttpGet(url));
                    LOG.info(" ...finished.");
                    if (response.getStatusLine().getStatusCode() == 200) {
                        try {
                            String fileName = getFileName(response);
                            if (fileName != null) {
                                copy(response.getEntity().getContent(), base + " - " + fileName);
                            } else {
                                LOG.error("  Couldn't find filename parameter of url " + url);
                            }
                        } catch (IOException e) {
                            LOG.error(e.getMessage(), e);
                        }
                    } else {
                        LOG.error("Could not download " + url + ". Status line is: " + response.getStatusLine());
                    }
                }
            } else {
                LOG.info("Cache entry " + base + " alrady exists");
            }
        }
    }

    private boolean createCacheEntry(String base) throws IOException {
        File file = new File(this.outputDir + "/cache/." + base);
        if (file.exists()) {
            return false;
        } else {
            return file.createNewFile();
        }
    }

    private String getFileName(HttpResponse response) {
        Header header = response.getFirstHeader("Content-Disposition");
        if (header == null) {
            return null;
        }
        HeaderElement[] elements = header.getElements();
        for (HeaderElement element : elements) {
            NameValuePair filenamePair = element.getParameterByName("filename");
            if (filenamePair != null) {
                return filenamePair.getValue();
            }
        }
        return null;
    }

    private void copy(InputStream is, String fileName) throws IOException {
        File file = new File(this.outputDir + "/" + fileName);
        if (file.exists()) {
            file = new File(this.outputDir + "/" + UUID.randomUUID().toString() + "__" + fileName);
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            IOUtils.copy(is, fos);
            LOG.info("  File written to " + file.toString());
        }
    }

    private List<NameValuePair> extractNextFormParameters(Document document) {
        final List<NameValuePair> params = new ArrayList<NameValuePair>();
        for (Element formElement : document.select("form[method=post]")) {
            if (isCorrectFormElement(formElement)) {
                for (Element element : formElement.select("input")) {
                    String name = element.attr("name");
                    String value = element.val();
                    if (!Strings.isNullOrEmpty(name)) {
                        params.add(new BasicNameValuePair(name, value));
                    }
                }
                break;
            }
        }
        return params;
    }

    private boolean isCorrectFormElement(Element element) {
        for (Element inputElement : element.select("input[type=submit]")) {
            if ("Next".equalsIgnoreCase(inputElement.val())) {
                return true;
            }
        }
        return false;
    }

    private PageParams extractURLs(String content) throws ParserException {
        Document document = Jsoup.parse(content);
        // Find date
        PageParams params = new PageParams();
        params.photos = extractGroups(document);
        params.nextParams = extractNextFormParameters(document);
        return params;
    }

    private List<Group> extractGroups(Document document) {
        List<Group> groups = newArrayList();
        for (Element panelElement : document.select(".panel_box")) {
            Group group = new Group();
            group.date = extractDate(panelElement);
            if (group.date != null) {
                group.date = group.date.replace('/', '-');
                group.date = group.date.replace(':', '-');
            }
            group.name = extractName(panelElement);
            group.urls.addAll(extractURLs(panelElement));
            groups.add(group);
        }
        return groups;

    }

    private String extractName(Element panelElement) {
        for (Element element : panelElement.select("p.name")) {
            return element.text();
        }
        return null;
    }

    private String extractDate(Element panelElement) {
        for (Element element : panelElement.select(".date2")) {
            String text = element.text();
            if (text.contains("/")) {
                return text;
            }
        }
        return null;
    }

    private List<String> extractURLs(Element panelElement) {
        List<String> urls = newArrayList();
        for (Element element : panelElement.select("a.fan1")) {
            String attr = element.attr("href");
            if (!Strings.isNullOrEmpty(attr)) {
                if (attr.contains("google") && attr.endsWith(".jpg")) {
                    urls.add(attr);
                }
            }
        }
        return urls;
    }

    private String fetchContent(List<NameValuePair> formParams) throws ClientProtocolException, IOException {
        HttpRequestBase request = null;
        if (formParams == null) {
            request = new HttpGet(this.url);
        } else {
            HttpPost post = new HttpPost(this.url);
            post.setEntity(new UrlEncodedFormEntity(formParams));
            request = post;
        }
        try {
            HttpResponse response = this.httpclient.execute(request);
            InputStream is = response.getEntity().getContent();
            Reader reader = new InputStreamReader(is, "UTF-8");
            String content = IOUtils.readLines(reader).stream().collect(joining("\n"));
            return content;
        } finally {
            request.releaseConnection();
        }
    }

    private static class PageParams {
        List<Group> photos = new ArrayList<Group>();
        List<NameValuePair> nextParams = new ArrayList<NameValuePair>();
    }

    private static class Group {
        String date = null;
        String name = null;
        List<String> urls = new ArrayList<String>();
    }

}
