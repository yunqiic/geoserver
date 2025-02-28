/* (c) 2021 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.ogcapi.stac;

import static org.geoserver.ogcapi.ConformanceClass.CQL2_ADVANCED;
import static org.geoserver.ogcapi.ConformanceClass.CQL2_ARITHMETIC;
import static org.geoserver.ogcapi.ConformanceClass.CQL2_BASIC;
import static org.geoserver.ogcapi.ConformanceClass.CQL2_BASIC_SPATIAL;
import static org.geoserver.ogcapi.ConformanceClass.CQL2_FUNCTIONS;
import static org.geoserver.ogcapi.ConformanceClass.CQL2_PROPERTY_PROPERTY;
import static org.geoserver.ogcapi.ConformanceClass.CQL2_SPATIAL;
import static org.geoserver.ogcapi.ConformanceClass.CQL2_TEXT;
import static org.geoserver.ogcapi.ConformanceClass.ECQL;
import static org.geoserver.ogcapi.ConformanceClass.ECQL_TEXT;
import static org.geoserver.ogcapi.ConformanceClass.FEATURES_FILTER;
import static org.geoserver.ogcapi.ConformanceClass.FILTER;
import static org.geoserver.opensearch.eo.store.OpenSearchAccess.EO_IDENTIFIER;
import static org.geoserver.opensearch.eo.store.OpenSearchQueries.getProductProperties;
import static org.geoserver.ows.URLMangler.URLType.RESOURCE;
import static org.geoserver.ows.util.ResponseUtils.buildURL;
import static org.geoserver.ows.util.ResponseUtils.urlEncode;

import io.swagger.v3.oas.models.OpenAPI;
import java.io.IOException;
import java.math.BigInteger;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.geoserver.catalog.Catalog;
import org.geoserver.config.GeoServer;
import org.geoserver.featurestemplating.builders.TemplateBuilder;
import org.geoserver.featurestemplating.builders.impl.RootBuilder;
import org.geoserver.ogcapi.APIBBoxParser;
import org.geoserver.ogcapi.APIDispatcher;
import org.geoserver.ogcapi.APIException;
import org.geoserver.ogcapi.APIFilterParser;
import org.geoserver.ogcapi.APIRequestInfo;
import org.geoserver.ogcapi.APIService;
import org.geoserver.ogcapi.ConformanceDocument;
import org.geoserver.ogcapi.DefaultContentType;
import org.geoserver.ogcapi.HTMLResponseBody;
import org.geoserver.ogcapi.JSONSchemaMessageConverter;
import org.geoserver.ogcapi.OGCAPIMediaTypes;
import org.geoserver.ogcapi.OpenAPIMessageConverter;
import org.geoserver.ogcapi.PaginationLinksBuilder;
import org.geoserver.ogcapi.Queryables;
import org.geoserver.ogcapi.Sortables;
import org.geoserver.opensearch.eo.OSEOInfo;
import org.geoserver.opensearch.eo.OpenSearchAccessProvider;
import org.geoserver.opensearch.eo.store.OpenSearchAccess;
import org.geoserver.ows.kvp.TimeParser;
import org.geoserver.platform.ServiceException;
import org.geotools.data.DataUtilities;
import org.geotools.data.FeatureSource;
import org.geotools.data.Query;
import org.geotools.data.geojson.GeoJSONReader;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.NameImpl;
import org.geotools.feature.visitor.UniqueVisitor;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.geotools.util.DateRange;
import org.geotools.util.logging.Logging;
import org.locationtech.jts.geom.Geometry;
import org.opengis.feature.Attribute;
import org.opengis.feature.Feature;
import org.opengis.feature.type.FeatureType;
import org.opengis.feature.type.Name;
import org.opengis.filter.Filter;
import org.opengis.filter.FilterFactory2;
import org.opengis.filter.PropertyIsEqualTo;
import org.opengis.filter.expression.Literal;
import org.opengis.filter.sort.SortBy;
import org.opengis.referencing.FactoryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** Implementation of OGC Features API service */
@APIService(
        service = "STAC",
        version = "1.0",
        landingPage = "ogc/stac",
        serviceClass = OSEOInfo.class)
@RequestMapping(path = APIDispatcher.ROOT_PATH + "/stac")
public class STACService {

    static final FilterFactory2 FF = CommonFactoryFinder.getFilterFactory2();

    public static final String STAC_VERSION = "1.0.0";

    public static final String FEATURE_CORE =
            "http://www.opengis.net/spec/ogcapi-features-1/1.0/conf/core";
    public static final String FEATURE_HTML =
            "http://www.opengis.net/spec/ogcapi-features-1/1.0/conf/html";
    public static final String FEATURE_GEOJSON =
            "http://www.opengis.net/spec/ogcapi-features-1/1.0/conf/geojson";
    public static final String FEATURE_OAS30 =
            "http://www.opengis.net/spec/ogcapi-features-1/1.0/conf/oas30";
    public static final String STAC_CORE = "https://api.stacspec.org/v1.0.0-beta.5/core";
    public static final String STAC_SEARCH = "https://api.stacspec.org/v1.0.0-beta.5/item-search";
    public static final String STAC_SEARCH_SORT =
            "https://api.stacspec.org/v1.0.0-beta.5/item-search#sort";
    public static final String STAC_SEARCH_FILTER =
            "https://api.stacspec.org/v1.0.0-beta.5/item-search#filter";
    public static final String STAC_FEATURES =
            "https://api.stacspec.org/spec/v1.0.0-beta.1/ogcapi-features";

    /** Container type: catalog */
    public static String TYPE_CATALOG = "Catalog";

    /** Container type: collection */
    public static String TYPE_COLLECTION = "Collection";

    private static final String DISPLAY_NAME = "SpatioTemporal Asset Catalog";

    static final Logger LOGGER = Logging.getLogger(STACService.class);

    private final GeoServer geoServer;
    private final OpenSearchAccessProvider accessProvider;
    private final STACTemplates templates;
    private final SampleFeatures sampleFeatures;
    private TimeParser timeParser = new TimeParser();
    private final APIFilterParser filterParser;

    public STACService(
            GeoServer geoServer,
            OpenSearchAccessProvider accessProvider,
            STACTemplates templates,
            APIFilterParser filterParser,
            SampleFeatures sampleFeatures) {
        this.geoServer = geoServer;
        this.accessProvider = accessProvider;
        this.templates = templates;
        this.filterParser = filterParser;
        this.sampleFeatures = sampleFeatures;
    }

    public OSEOInfo getService() {
        return geoServer.getService(OSEOInfo.class);
    }

    private Catalog getCatalog() {
        return geoServer.getCatalog();
    }

    @GetMapping(name = "getLandingPage")
    @ResponseBody
    @HTMLResponseBody(templateName = "landingPage.ftl", fileName = "landingPage.html")
    public STACLandingPage getLandingPage() throws IOException {
        return new STACLandingPage(
                getService(), "ogc/stac", conformance().getConformsTo(), getCollectionIds());
    }

    @GetMapping(path = "conformance", name = "getConformanceDeclaration")
    @ResponseBody
    @HTMLResponseBody(templateName = "conformance.ftl", fileName = "conformance.html")
    public ConformanceDocument conformance() {
        List<String> classes =
                Arrays.asList(
                        FEATURE_CORE,
                        FEATURE_OAS30,
                        FEATURE_HTML,
                        FEATURE_GEOJSON,
                        STAC_CORE,
                        STAC_FEATURES,
                        STAC_SEARCH,
                        STAC_SEARCH_FILTER,
                        STAC_SEARCH_SORT,
                        FEATURES_FILTER,
                        FILTER,
                        ECQL,
                        ECQL_TEXT,
                        CQL2_BASIC,
                        CQL2_ADVANCED,
                        CQL2_ARITHMETIC,
                        CQL2_PROPERTY_PROPERTY,
                        CQL2_BASIC_SPATIAL,
                        CQL2_SPATIAL,
                        CQL2_FUNCTIONS,
                        /* CQL2_TEMPORAL excluded for now, no support for all operators */
                        /* CQL2_ARRAY excluded, no support for array operations now */
                        CQL2_TEXT
                        /* CQL2_JSON very different from the binding we have */ );
        return new ConformanceDocument(DISPLAY_NAME, classes);
    }

    @GetMapping(
            path = "api",
            name = "getApi",
            produces = {
                OpenAPIMessageConverter.OPEN_API_MEDIA_TYPE_VALUE,
                "application/x-yaml",
                MediaType.TEXT_XML_VALUE
            })
    @ResponseBody
    @HTMLResponseBody(templateName = "api.ftl", fileName = "api.html")
    public OpenAPI api() throws IOException {
        return new STACAPIBuilder(accessProvider).build(getService());
    }

    @GetMapping(path = "collections", name = "getCollections")
    @ResponseBody
    @HTMLResponseBody(templateName = "collections.ftl", fileName = "collections.html")
    public CollectionsResponse collections() throws IOException {
        Query q = new Query();
        q.setFilter(getEnabledFilter());
        FeatureCollection<FeatureType, Feature> collections =
                accessProvider.getOpenSearchAccess().getCollectionSource().getFeatures(q);
        return new CollectionsResponse(collections);
    }

    @GetMapping(path = "collections/{collectionId}", name = "getCollection")
    @ResponseBody
    @HTMLResponseBody(templateName = "collection.ftl", fileName = "collection.html")
    public CollectionResponse collection(@PathVariable("collectionId") String collectionId)
            throws IOException {
        Feature collection = getCollection(collectionId);
        return new CollectionResponse(collection);
    }

    private Feature getCollection(String collectionId) throws IOException {
        Query q = new Query();
        q.setFilter(
                FF.and(
                        getEnabledFilter(),
                        FF.equals(FF.property(EO_IDENTIFIER), FF.literal(collectionId))));
        FeatureCollection<FeatureType, Feature> collections =
                accessProvider.getOpenSearchAccess().getCollectionSource().getFeatures(q);
        Feature collection = DataUtilities.first(collections);
        if (collection == null)
            throw new APIException(
                    ServiceException.INVALID_PARAMETER_VALUE,
                    "Collection not found: " + collectionId,
                    HttpStatus.NOT_FOUND);
        return collection;
    }

    @SuppressWarnings("unchecked")
    private Set<String> getCollectionIds() throws IOException {
        Query q = new Query();
        q.setFilter(getEnabledFilter());
        FeatureCollection<FeatureType, Feature> collections =
                accessProvider.getOpenSearchAccess().getCollectionSource().getFeatures(q);
        FeatureType schema = collections.getSchema();
        // for unique visitor to work against complex features, full namespace has to be provided
        Name name = new NameImpl(schema.getName().getNamespaceURI(), "name");
        UniqueVisitor unique = new UniqueVisitor(FF.property(schema.getDescriptor(name).getName()));
        collections.accepts(unique, null);
        Set<Attribute> values = unique.getUnique();
        return values.stream().map(a -> (String) a.getValue()).collect(Collectors.toSet());
    }

    @GetMapping(path = "collections/{collectionId}/items/{itemId:.+}", name = "getItem")
    @ResponseBody
    @DefaultContentType(OGCAPIMediaTypes.GEOJSON_VALUE)
    public ItemResponse item(
            @PathVariable(name = "collectionId") String collectionId,
            @PathVariable(name = "itemId") String itemId)
            throws Exception {
        // run just to check the collection exists, and throw an appropriate exception otherwise
        getCollection(collectionId);

        Query q = new Query();
        q.setFilter(
                FF.and(
                        getEnabledFilter(),
                        FF.equals(FF.property("identifier"), FF.literal(itemId))));
        q.setProperties(getProductProperties(accessProvider.getOpenSearchAccess()));

        FeatureSource<FeatureType, Feature> products =
                accessProvider.getOpenSearchAccess().getProductSource();
        FeatureCollection<FeatureType, Feature> items = products.getFeatures(q);
        Feature item = DataUtilities.first(items);
        if (item == null) {
            throw new APIException(
                    ServiceException.INVALID_PARAMETER_VALUE,
                    "Could not locate item " + itemId,
                    HttpStatus.NOT_FOUND);
        }
        return new ItemResponse(collectionId, item);
    }

    @GetMapping(path = "collections/{collectionId}/items", name = "getItems")
    @ResponseBody
    @DefaultContentType(OGCAPIMediaTypes.GEOJSON_VALUE)
    public ItemsResponse items(
            @PathVariable(name = "collectionId") String collectionId,
            @RequestParam(name = "startIndex", required = false, defaultValue = "0") int startIndex,
            @RequestParam(name = "limit", required = false) Integer requestedLimit,
            @RequestParam(name = "bbox", required = false) String bbox,
            @RequestParam(name = "datetime", required = false) String datetime,
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "filter-lang", required = false) String filterLanguage,
            @RequestParam(name = "sortby", required = false) SortBy[] sortBy)
            throws Exception {
        // check the collection is enabled
        Feature collection = getCollection(collectionId);
        if (Boolean.FALSE.equals(collection.getProperty("enabled").getValue())) {
            throw new APIException(
                    ServiceException.INVALID_PARAMETER_VALUE,
                    "Collection " + collectionId + " is not avialable",
                    HttpStatus.NOT_FOUND);
        }
        // query the items based on the request parameters
        QueryResult qr =
                queryItems(
                        Arrays.asList(collectionId),
                        startIndex,
                        requestedLimit,
                        bbox,
                        null,
                        datetime,
                        filter,
                        filterLanguage,
                        sortBy,
                        false);

        // build the links
        ItemsResponse response =
                new ItemsResponse(
                        collectionId, qr.getItems(), qr.getNumberMatched(), qr.getReturned());
        String path = "ogc/stac/collections/" + urlEncode(collectionId) + "/items";
        PaginationLinksBuilder linksBuilder =
                new PaginationLinksBuilder(
                        path,
                        startIndex,
                        qr.getQuery().getMaxFeatures(),
                        qr.getReturned(),
                        qr.getNumberMatched().longValue());
        response.setPrevious(linksBuilder.getPrevious());
        response.setNext(linksBuilder.getNext());
        response.setSelf(linksBuilder.getSelf());

        return response;
    }

    @GetMapping(path = "search", name = "searchGet")
    @ResponseBody
    @DefaultContentType(OGCAPIMediaTypes.GEOJSON_VALUE)
    public SearchResponse searchGet(
            @RequestParam(name = "collections", required = false) List<String> collectionIds,
            @RequestParam(name = "startIndex", required = false, defaultValue = "0") int startIndex,
            @RequestParam(name = "limit", required = false) Integer requestedLimit,
            @RequestParam(name = "bbox", required = false) String bbox,
            @RequestParam(name = "intersects", required = false) String intersects,
            @RequestParam(name = "datetime", required = false) String datetime,
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "filter-lang", required = false) String filterLanguage,
            @RequestParam(name = "sortby", required = false) SortBy[] sortBy)
            throws Exception {
        // query the items based on the request parameters
        QueryResult qr =
                queryItems(
                        collectionIds,
                        startIndex,
                        requestedLimit,
                        bbox,
                        intersects,
                        datetime,
                        filter,
                        filterLanguage,
                        sortBy,
                        true);

        // build the links
        SearchResponse response =
                new SearchResponse(qr.getItems(), qr.getNumberMatched(), qr.getReturned());
        String path = "ogc/stac/search";
        PaginationLinksBuilder linksBuilder =
                new PaginationLinksBuilder(
                        path,
                        startIndex,
                        qr.getQuery().getMaxFeatures(),
                        qr.getReturned(),
                        qr.getNumberMatched().longValue());
        response.setPrevious(linksBuilder.getPrevious());
        response.setNext(linksBuilder.getNext());
        response.setSelf(linksBuilder.getSelf());

        return response;
    }

    @PostMapping(path = "search", name = "searchPost")
    @ResponseBody
    @DefaultContentType(OGCAPIMediaTypes.GEOJSON_VALUE)
    public SearchResponse searchPost(@RequestBody SearchQuery sq) throws Exception {
        FeatureSource<FeatureType, Feature> source =
                accessProvider.getOpenSearchAccess().getProductSource();

        // request parsing
        FilterMerger filters = new FilterMerger();

        List<String> collectionIds = sq.getCollections();
        addCollectionsFilter(filters, collectionIds, true);

        double[] bbox = sq.getBbox();
        if (bbox != null) {
            filters.add(APIBBoxParser.toFilter(bbox, DefaultGeographicCRS.WGS84));
        }
        if (sq.getIntersection() != null) {
            filters.add(FF.intersects(FF.property(""), FF.literal(sq.getIntersection())));
        }
        if (sq.getDatetime() != null) {
            filters.add(buildTimeFilter(sq.getDatetime()));
        }
        if (sq.getFilter() != null) {
            Filter mapped = parseFilter(sq.getCollections(), sq.getFilter(), sq.getFilterLang());
            filters.add(mapped);
        }
        // keep only enabled products
        filters.add(getEnabledFilter());

        Query q = new Query();
        q.setStartIndex(sq.getStartIndex());
        int limit = getLimit(sq.getLimit());
        q.setMaxFeatures(limit);
        q.setFilter(filters.and());

        // query the items based on the request parameters
        QueryResult qr = queryItems(source, q);

        // build the links
        SearchResponse response =
                new SearchResponse(qr.getItems(), qr.getNumberMatched(), qr.getReturned());
        String path = "ogc/stac/search";
        PaginationLinksBuilder linksBuilder =
                new PaginationLinksBuilder(
                        path,
                        Optional.ofNullable(sq.getStartIndex()).orElse(0),
                        qr.getQuery().getMaxFeatures(),
                        qr.getReturned(),
                        qr.getNumberMatched().longValue());
        response.setSelf(linksBuilder.getSelf());
        response.setPost(true);
        response.setPreviousBody(linksBuilder.getPreviousMap(false));
        response.setNextBody(linksBuilder.getNextMap(false));

        return response;
    }

    private void addCollectionsFilter(
            FilterMerger filters, List<String> collectionIds, boolean excludeDisabledCollection)
            throws IOException {
        List<String> disabledIds =
                excludeDisabledCollection
                        ? getDisabledCollections(collectionIds)
                        : Collections.emptyList();

        if (collectionIds != null && !collectionIds.isEmpty()) {
            collectionIds.removeAll(disabledIds);
            filters.add(getCollectionsFilter(collectionIds));
        } else if (!disabledIds.isEmpty()) {
            // exclude disabled collections
            filters.add(FF.not(getCollectionsFilter(disabledIds)));
        }
    }

    public PropertyIsEqualTo getEnabledFilter() {
        return FF.equals(FF.property(OpenSearchAccess.ENABLED), FF.literal(true));
    }

    public Filter parseFilter(List<String> collectionIds, String filter, String filterLang)
            throws IOException {
        Filter parsed = filterParser.parse(filter, filterLang);
        return new TemplatePropertyMapper(templates, sampleFeatures)
                .mapProperties(collectionIds, parsed);
    }

    /** TODO: Factor out this method into a Query mapper object */
    private QueryResult queryItems(
            List<String> collectionIds,
            int startIndex,
            Integer requestedLimit,
            String bbox,
            String intersects,
            String datetime,
            String filter,
            String filterLanguage,
            SortBy[] sortby,
            boolean excludeDisabledCollection)
            throws IOException, FactoryException, ParseException {
        // request parsing
        FilterMerger filters = new FilterMerger();

        addCollectionsFilter(filters, collectionIds, excludeDisabledCollection);
        if (bbox != null) {
            filters.add(APIBBoxParser.toFilter(bbox, DefaultGeographicCRS.WGS84));
        }
        if (intersects != null) {
            Geometry geometry = GeoJSONReader.parseGeometry(intersects);
            filters.add(FF.intersects(FF.property(""), FF.literal(geometry)));
        }
        if (datetime != null) {
            filters.add(buildTimeFilter(datetime));
        }
        if (filter != null) {
            Filter mapped = parseFilter(collectionIds, filter, filterLanguage);
            filters.add(mapped);
        }
        // keep only enabled products
        filters.add(getEnabledFilter());

        Query q = new Query();
        q.setStartIndex(startIndex);
        int limit = getLimit(requestedLimit);
        q.setMaxFeatures(limit);
        q.setFilter(filters.and());
        q.setProperties(getProductProperties(accessProvider.getOpenSearchAccess()));
        q.setSortBy(mapSortProperties(collectionIds, sortby));

        FeatureSource<FeatureType, Feature> source =
                accessProvider.getOpenSearchAccess().getProductSource();
        return queryItems(source, q);
    }

    private SortBy[] mapSortProperties(List<String> collectionIds, SortBy[] sortby)
            throws IOException {
        // nothing to map, easy way out
        if (sortby == null) return null;

        // do we map for a specific collection, or have to deal with multiple ones?
        FeatureType itemsSchema =
                accessProvider.getOpenSearchAccess().getProductSource().getSchema();
        TemplateBuilder builder;
        if (collectionIds == null || collectionIds.size() > 1) {
            // right now assuming multiple collections means using search, where the
            // sortables are generic
            builder = templates.getItemTemplate(null);
        } else {
            builder = templates.getItemTemplate(collectionIds.get(0));
        }
        STACSortablesMapper mapper = new STACSortablesMapper(builder, itemsSchema);
        return mapper.map(sortby);
    }

    private List<String> getDisabledCollections(List<String> collectionIds) throws IOException {
        Query q = new Query();
        Filter filter = FF.equals(FF.property(OpenSearchAccess.ENABLED), FF.literal(false));
        if (collectionIds != null && !collectionIds.isEmpty()) {
            List<Filter> filters = new ArrayList<>();
            filters.add(filter);

            filters.addAll(
                    collectionIds.stream()
                            .map(cid -> FF.equals(FF.property(EO_IDENTIFIER), FF.literal(cid)))
                            .collect(Collectors.toList()));
            filter = FF.and(filters);
        }
        q.setFilter(filter);
        q.setProperties(Arrays.asList(FF.property(EO_IDENTIFIER)));
        FeatureCollection<FeatureType, Feature> collections =
                accessProvider.getOpenSearchAccess().getCollectionSource().getFeatures(q);
        return DataUtilities.list(collections).stream()
                .map(f -> (String) f.getProperty(EO_IDENTIFIER).getValue())
                .collect(Collectors.toList());
    }

    private QueryResult queryItems(FeatureSource<FeatureType, Feature> source, Query q)
            throws IOException {
        // get the items
        FeatureCollection<FeatureType, Feature> items = source.getFeatures(q);

        // the counts
        Query matchedQuery = new Query(q);
        matchedQuery.setMaxFeatures(-1);
        matchedQuery.setStartIndex(0);
        int matched = source.getCount(matchedQuery);
        int returned = items.size();

        return new QueryResult(q, items, BigInteger.valueOf(matched), returned);
    }

    static Filter getCollectionsFilter(List<String> collectionIds) {
        FilterMerger filters = new FilterMerger();
        collectionIds.stream()
                .map(id -> FF.equals(FF.property("parentIdentifier"), FF.literal(id)))
                .forEach(f -> filters.add(f));
        return filters.or();
    }

    /**
     * Returns an actual limit based on the
     *
     * @param requestedLimit
     * @return
     */
    private int getLimit(Integer requestedLimit) {
        OSEOInfo oseo = getService();
        int serviceMax = oseo.getMaximumRecordsPerPage();
        if (requestedLimit == null) return oseo.getRecordsPerPage();
        return Math.min(serviceMax, requestedLimit);
    }

    private Filter buildTimeFilter(String time) throws ParseException, IOException {
        Collection times = timeParser.parse(time);
        if (times.isEmpty() || times.size() > 1) {
            throw new ServiceException(
                    "Invalid time specification, must be a single time, or a time range",
                    ServiceException.INVALID_PARAMETER_VALUE,
                    "time");
        }

        Object timeSpec = times.iterator().next();

        if (timeSpec instanceof Date) {
            // range containment
            return FF.between(
                    FF.literal(timeSpec), FF.property("timeStart"), FF.property("timeEnd"));
        } else if (timeSpec instanceof DateRange) {
            // range overlap filter
            DateRange dateRange = (DateRange) timeSpec;
            Literal before = FF.literal(dateRange.getMinValue());
            Literal after = FF.literal(dateRange.getMaxValue());
            Filter lower = FF.lessOrEqual(FF.property("timeStart"), after);
            Filter upper = FF.greaterOrEqual(FF.property("timeEnd"), before);
            return FF.and(lower, upper);
        } else {
            throw new IllegalArgumentException("Cannot build time filter out of " + timeSpec);
        }
    }

    @GetMapping(
            path = "collections/{collectionId}/queryables",
            name = "getCollectionQueryables",
            produces = JSONSchemaMessageConverter.SCHEMA_TYPE_VALUE)
    @ResponseBody
    @HTMLResponseBody(templateName = "queryables-collection.ftl", fileName = "queryables.html")
    public Queryables collectionQueryables(@PathVariable(name = "collectionId") String collectionId)
            throws IOException {
        // check the collection is there
        getCollection(collectionId);
        String id =
                buildURL(
                        APIRequestInfo.get().getBaseURL(),
                        "ogc/stac/collections/" + urlEncode(collectionId) + "/queryables",
                        null,
                        RESOURCE);
        Queryables queryables =
                new STACQueryablesBuilder(
                                id,
                                templates.getItemTemplate(collectionId),
                                sampleFeatures.getSchema(),
                                sampleFeatures.getSample(collectionId))
                        .getQueryables();
        queryables.setCollectionId(collectionId);
        return queryables;
    }

    @GetMapping(
            path = "collections/{collectionId}/sortables",
            name = "getCollectionSortables",
            produces = JSONSchemaMessageConverter.SCHEMA_TYPE_VALUE)
    @ResponseBody
    @HTMLResponseBody(templateName = "sortables-collection.ftl", fileName = "sortables.html")
    public Sortables collectionSortables(@PathVariable(name = "collectionId") String collectionId)
            throws IOException {
        // check the collection is there
        getCollection(collectionId);
        String id =
                buildURL(
                        APIRequestInfo.get().getBaseURL(),
                        "ogc/stac/collections/" + urlEncode(collectionId) + "/sortables",
                        null,
                        RESOURCE);
        FeatureType itemsSchema =
                accessProvider.getOpenSearchAccess().getProductSource().getSchema();
        RootBuilder template = this.templates.getItemTemplate(collectionId);
        Sortables sortables = new STACSortablesMapper(template, itemsSchema, id).getSortables();
        sortables.setCollectionId(collectionId);
        return sortables;
    }

    @GetMapping(
            path = "queryables",
            name = "getSearchQueryables",
            produces = JSONSchemaMessageConverter.SCHEMA_TYPE_VALUE)
    @ResponseBody
    @HTMLResponseBody(templateName = "queryables-global.ftl", fileName = "queryables.html")
    public Queryables searchQueryables() throws IOException {
        String baseURL = APIRequestInfo.get().getBaseURL();
        String id = buildURL(baseURL, "ogc/stac/queryables", null, RESOURCE);
        LOGGER.severe(
                "Should consider the various collection specific templates here, and decide what to do for queriables that are in one collection but not in others (replace with null and simplify filter?)");
        Queryables queryables =
                new STACQueryablesBuilder(
                                id,
                                templates.getItemTemplate(null),
                                sampleFeatures.getSchema(),
                                sampleFeatures.getSample(null))
                        .getQueryables();
        return queryables;
    }

    @GetMapping(
            path = "sortables",
            name = "getSearchSortables",
            produces = JSONSchemaMessageConverter.SCHEMA_TYPE_VALUE)
    @ResponseBody
    @HTMLResponseBody(templateName = "sortables-global.ftl", fileName = "sortables.html")
    public Sortables searchSortables() throws IOException {
        String baseURL = APIRequestInfo.get().getBaseURL();
        String id = buildURL(baseURL, "ogc/stac/sortables", null, RESOURCE);
        LOGGER.severe(
                "Should consider the various collection specific templates here, and decide what to do for sortables that are in one collection but not in others (replace with null and simplify filter?)");
        FeatureType itemsSchema =
                accessProvider.getOpenSearchAccess().getProductSource().getSchema();
        RootBuilder template = this.templates.getItemTemplate(null);
        Sortables sortables = new STACSortablesMapper(template, itemsSchema, id).getSortables();
        return sortables;
    }
}
