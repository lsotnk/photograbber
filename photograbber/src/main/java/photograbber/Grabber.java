package photograbber;

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
import org.htmlparser.Node;
import org.htmlparser.Parser;
import org.htmlparser.Tag;
import org.htmlparser.util.NodeList;
import org.htmlparser.util.ParserException;
import org.htmlparser.util.SimpleNodeIterator;
import org.htmlparser.visitors.NodeVisitor;

public class Grabber {

    public static void main(String[] args) throws Exception {
        Grabber grabber = new Grabber("http://???????????", "f:/GPTemp", 60);
        try {
            grabber.run();
        } finally {
            grabber.dispose();
        }

    }

    private String url;
    private String outputFolder;
    private HttpClient httpclient;
    private int maxDepth;

    public Grabber(String url, String outputFolder, int maxDepth) {
        super();
        this.url = url;
        this.outputFolder = outputFolder;
        this.httpclient = HttpClientBuilder.create().build();
        this.maxDepth = maxDepth;
    }

    public void run() throws Exception {
        PageParams params = null;
        for (int i = 0; i < this.maxDepth; i++) {
            System.out.println("================ PAGE " + (i + 1) + " ================");
            String content = fetchContent(params == null ? null : params.nextParams);
            params = extractURLs(content);
            System.out.println("Found " + params.photos.size() + " groups");
            System.out.println("Next params: " + params.nextParams);
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
                System.out.println("GroupTest " + group.date + " is empty");
                continue;
            }
            if (group.date == null) {
                System.err.println("Date is null");
                continue;
            }
            String base = group.name + " - " + group.date;
            if (createCacheEntry(base)) {
                System.out.println("Processing group " + base + " with " + group.urls.size() + " photos");
                System.out.println("  Cache entry " + group.date + " created");
                for (String url : group.urls) {
                    System.out.print("  Downloading " + url + "...");
                    HttpResponse response = this.httpclient.execute(new HttpGet(url));
                    System.out.println(" finished.");
                    if (response.getStatusLine().getStatusCode() == 200) {
                        try {
                            String fileName = getFileName(response);
                            if (fileName != null) {
                                copy(response.getEntity().getContent(), base + " - " + fileName);
                            } else {
                                System.err.println("  Couldn't find filename parameter of url " + url);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    } else {
                        System.err.println("Could not download " + url + ". Status line is: " + response.getStatusLine());
                    }
                }
            } else {
                System.out.println("Cache entry " + base + " alrady exists");
            }
        }
    }

    private boolean createCacheEntry(String base) throws IOException {
        File file = new File(this.outputFolder + "/cache/." + base);
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
        File file = new File(this.outputFolder + "/" + fileName);
        if (file.exists()) {
            file = new File(this.outputFolder + "/" + UUID.randomUUID().toString() + "__" + fileName);
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            IOUtils.copy(is, fos);
            System.out.println("  File written to " + file.toString());
        }
    }

    private List<NameValuePair> extractNextFormParameters(Tag tag) {
        final List<NameValuePair> params = new ArrayList<NameValuePair>();
        tag.accept(new NodeVisitor(true, false) {
            @Override
            public void visitTag(Tag tag) {
                if (tag.getTagName().equalsIgnoreCase("input")) {
                    String name = tag.getAttribute("name");
                    String value = tag.getAttribute("value");
                    if (name != null && value != null) {
                        params.add(new BasicNameValuePair(name, value));
                    }
                }
            }
        });
        return params;
    }

    private PageParams extractURLs(String content) throws ParserException {
        Parser parser = new Parser(content);
        NodeList list = parser.parse(null);
        SimpleNodeIterator it = list.elements();
        final PageParams pageParams = new PageParams();
        while (it.hasMoreNodes()) {
            Node nextNode = it.nextNode();
            nextNode.accept(new NodeVisitor(true, true) {
                @Override
                public void visitTag(Tag tag) {
                    if (tag.getTagName().equalsIgnoreCase("div")) {
                        if ("panel panel-default".equalsIgnoreCase(tag.getAttribute("class"))) {
                            final Group group = new Group();
                            tag.accept(new NodeVisitor(true, true) {
                                @Override
                                public void visitTag(Tag tag) {
                                    if ("date2".equalsIgnoreCase(tag.getAttribute("class"))) {
                                        if (group.date == null) {
                                            String date = tag.toPlainTextString();
                                            date = date.replace('/', '-');
                                            date = date.replace(':', '-');
                                            group.date = date;
                                        }
                                    }
                                    if ("p".equalsIgnoreCase(tag.getTagName())) {
                                        if ("name".equalsIgnoreCase(tag.getAttribute("class"))) {
                                            group.name = tag.toPlainTextString();
                                        }
                                    }
                                };
                            });
                            tag.accept(new NodeVisitor(true, true) {
                                @Override
                                public void visitTag(Tag tag) {
                                    String src = tag.getAttribute("href");
                                    if (src != null && src.contains("googleusercontent") && src.contains(".jpg")) {
                                        group.urls.add(src);
                                    }
                                };
                            });
                            pageParams.photos.add(group);
                        }

                    } else if (tag.getTagName().equalsIgnoreCase("form")) {
                        if ("view".equalsIgnoreCase(tag.getAttribute("action"))) {
                            pageParams.nextParams.addAll(extractNextFormParameters(tag));
                        }
                    }
                }
            });
        }
        return pageParams;
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
