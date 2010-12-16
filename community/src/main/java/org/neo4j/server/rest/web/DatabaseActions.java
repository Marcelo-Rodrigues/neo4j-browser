/**
 * Copyright (c) 2002-2010 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.neo4j.server.rest.web;

import org.neo4j.graphalgo.GraphAlgoFactory;
import org.neo4j.graphalgo.PathFinder;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.Expander;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipExpander;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.TransactionFailureException;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.traversal.TraversalDescription;
import org.neo4j.helpers.collection.IterableWrapper;
import org.neo4j.kernel.Traversal;
import org.neo4j.server.database.Database;
import org.neo4j.server.rest.domain.EndNodeNotFoundException;
import org.neo4j.server.rest.domain.RelationshipExpanderBuilder;
import org.neo4j.server.rest.domain.StartNodeNotFoundException;
import org.neo4j.server.rest.domain.StartNodeSameAsEndNodeException;
import org.neo4j.server.rest.domain.StorageActions.TraverserReturnType;
import org.neo4j.server.rest.domain.TraversalDescriptionBuilder;
import org.neo4j.server.rest.repr.*;

import javax.ws.rs.core.Response;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

// TODO: move this to another package. domain?
public class DatabaseActions
{
    private final GraphDatabaseService graphDb;

    DatabaseActions( Database database )
    {
        this.graphDb = database.graph;
    }

    private Node node( long id ) throws NodeNotFoundException
    {
        try
        {
            return graphDb.getNodeById( id );
        } catch ( NotFoundException e )
        {
            throw new NodeNotFoundException();
        }
    }

    private Relationship relationship( long id ) throws RelationshipNotFoundException
    {
        try
        {
            return graphDb.getRelationshipById( id );
        } catch ( NotFoundException e )
        {
            throw new RelationshipNotFoundException();
        }
    }

    private <T extends PropertyContainer> T set( T entity, Map<String, Object> properties )
            throws PropertyValueException
    {
        if ( properties != null )
        {
            for ( Map.Entry<String, Object> property : properties.entrySet() )
            {
                try
                {
                    entity.setProperty( property.getKey(), property( property.getValue() ) );
                } catch ( IllegalArgumentException ex )
                {
                    throw new PropertyValueException( property.getKey(), property.getValue() );
                }
            }
        }
        return entity;
    }

    private Object property( Object value )
    {
        if ( value instanceof Collection<?> )
        {
            Collection<?> collection = (Collection<?>)value;
            Object[] array = null;
            Iterator<?> objects = collection.iterator();
            for ( int i = 0; objects.hasNext(); i++ )
            {
                Object object = objects.next();
                if ( array == null )
                {
                    array = (Object[])Array.newInstance( object.getClass(), collection.size() );
                }
                array[ i ] = object;
            }
            return array;
        } else
        {
            return value;
        }
    }

    private <T extends PropertyContainer> T clear( T entity )
    {
        for ( String key : entity.getPropertyKeys() )
        {
            entity.removeProperty( key );
        }
        return entity;
    }

    // API

    public DatabaseRepresentation root()
    {
        return new DatabaseRepresentation( graphDb );
    }

    // Nodes

    public NodeRepresentation createNode( Map<String, Object> properties )
            throws PropertyValueException
    {
        final NodeRepresentation result;
        Transaction tx = graphDb.beginTx();
        try
        {
            result = new NodeRepresentation( set( graphDb.createNode(), properties ) );
            tx.success();
        } finally
        {
            tx.finish();
        }
        return result;
    }

    public NodeRepresentation getNode( long nodeId ) throws NodeNotFoundException
    {
        return new NodeRepresentation( node( nodeId ) );
    }

    public void deleteNode( long nodeId ) throws NodeNotFoundException, OperationFailureException
    {
        Node node = node( nodeId );
        Transaction tx = graphDb.beginTx();
        try
        {
            node.delete();
            tx.success();
        } finally
        {
            try
            {
                tx.finish();
            } catch ( TransactionFailureException e )
            {
                throw new OperationFailureException();
            }
        }
    }

    public NodeRepresentation getReferenceNode()
    {
        return new NodeRepresentation( graphDb.getReferenceNode() );
    }

    // Node properties

    public Representation getNodeProperty( long nodeId, String key ) throws NodeNotFoundException,
            NoSuchPropertyException
    {
        Node node = node( nodeId );
        try
        {
            return PropertiesRepresentation.value( node.getProperty( key ) );
        } catch ( NotFoundException e )
        {
            throw new NoSuchPropertyException( node, key );
        }
    }

    public void setNodeProperty( long nodeId, String key, Object value )
            throws PropertyValueException, NodeNotFoundException
    {
        Node node = node( nodeId );
        value = property( value );
        Transaction tx = graphDb.beginTx();
        try
        {
            node.setProperty( key, value );
            tx.success();
        } catch ( IllegalArgumentException e )
        {
            throw new PropertyValueException( key, value );
        } finally
        {
            tx.finish();
        }
    }

    public void removeNodeProperty( long nodeId, String key ) throws NodeNotFoundException,
            NoSuchPropertyException
    {
        Node node = node( nodeId );
        Transaction tx = graphDb.beginTx();
        try
        {
            if ( node.removeProperty( key ) == null )
            {
                throw new NoSuchPropertyException( node, key );
            }
            tx.success();
        } finally
        {
            tx.finish();
        }
    }

    public PropertiesRepresentation getAllNodeProperties( long nodeId )
            throws NodeNotFoundException
    {
        return new PropertiesRepresentation( node( nodeId ) );
    }

    public void setAllNodeProperties( long nodeId, Map<String, Object> properties )
            throws PropertyValueException, NodeNotFoundException
    {
        Node node = node( nodeId );
        Transaction tx = graphDb.beginTx();
        try
        {
            set( clear( node ), properties );
            tx.success();
        } finally
        {
            tx.finish();
        }
    }

    public void removeAllNodeProperties( long nodeId ) throws NodeNotFoundException
    {
        Node node = node( nodeId );
        Transaction tx = graphDb.beginTx();
        try
        {
            clear( node );
            tx.success();
        } finally
        {
            tx.finish();
        }
    }

    public String[] getNodeIndexNames()
    {
        return graphDb.index().nodeIndexNames();
    }

    public String[] getRelationshipIndexNames()
    {
        return graphDb.index().relationshipIndexNames();
    }

    // Relationships

    public enum RelationshipDirection
    {
        all( Direction.BOTH ),
        in( Direction.INCOMING ),
        out( Direction.OUTGOING );
        final Direction internal;

        private RelationshipDirection( Direction internal )
        {
            this.internal = internal;
        }
    }

    public RelationshipRepresentation createRelationship( long startNodeId, long endNodeId,
                                                          String type,
                                                          Map<String, Object> properties ) throws StartNodeNotFoundException,
            EndNodeNotFoundException, StartNodeSameAsEndNodeException, PropertyValueException
    {
        if ( startNodeId == endNodeId )
        {
            throw new StartNodeSameAsEndNodeException();
        }
        Node start, end;
        try
        {
            start = node( startNodeId );
        } catch ( NodeNotFoundException e )
        {
            throw new StartNodeNotFoundException();
        }
        try
        {
            end = node( endNodeId );
        } catch ( NodeNotFoundException e )
        {
            throw new EndNodeNotFoundException();
        }
        final RelationshipRepresentation result;
        Transaction tx = graphDb.beginTx();
        try
        {
            result = new RelationshipRepresentation( set( start.createRelationshipTo( end,
                    DynamicRelationshipType.withName( type ) ), properties ) );
            tx.success();
        } finally
        {
            tx.finish();
        }
        return result;
    }

    public RelationshipRepresentation getRelationship( long relationshipId )
            throws RelationshipNotFoundException
    {
        return new RelationshipRepresentation( relationship( relationshipId ) );
    }

    public void deleteRelationship( long relationshipId ) throws RelationshipNotFoundException
    {
        Relationship relationship = relationship( relationshipId );
        Transaction tx = graphDb.beginTx();
        try
        {
            relationship.delete();
            tx.success();
        } finally
        {
            tx.finish();
        }
    }

    public ListRepresentation getNodeRelationships( long nodeId, RelationshipDirection direction,
                                                    Collection<String> types ) throws NodeNotFoundException
    {
        Node node = node( nodeId );
        Expander expander;
        if ( types.isEmpty() )
        {
            expander = Traversal.expanderForAllTypes( direction.internal );
        } else
        {
            expander = Traversal.emptyExpander();
            for ( String type : types )
            {
                expander = expander.add( DynamicRelationshipType.withName( type ),
                        direction.internal );
            }
        }
        return RelationshipRepresentation.list( expander.expand( node ) );
    }

    // Relationship properties

    public PropertiesRepresentation getAllRelationshipProperties( long relationshipId )
            throws RelationshipNotFoundException
    {
        return new PropertiesRepresentation( relationship( relationshipId ) );
    }

    public Representation getRelationshipProperty( long relationshipId, String key )
            throws NoSuchPropertyException, RelationshipNotFoundException
    {
        Relationship relationship = relationship( relationshipId );
        try
        {
            return PropertiesRepresentation.value( relationship.getProperty( key ) );
        } catch ( NotFoundException e )
        {
            throw new NoSuchPropertyException( relationship, key );
        }
    }

    public void setAllRelationshipProperties( long relationshipId, Map<String, Object> properties )
            throws PropertyValueException, RelationshipNotFoundException
    {
        Relationship relationship = relationship( relationshipId );
        Transaction tx = graphDb.beginTx();
        try
        {
            set( clear( relationship ), properties );
            tx.success();
        } finally
        {
            tx.finish();
        }
    }

    public void setRelationshipProperty( long relationshipId, String key, Object value )
            throws PropertyValueException, RelationshipNotFoundException
    {
        Relationship relationship = relationship( relationshipId );
        value = property( value );
        Transaction tx = graphDb.beginTx();
        try
        {
            relationship.setProperty( key, value );
            tx.success();
        } catch ( IllegalArgumentException e )
        {
            throw new PropertyValueException( key, value );
        } finally
        {
            tx.finish();
        }
    }

    public void removeAllRelationshipProperties( long relationshipId )
            throws RelationshipNotFoundException
    {
        Relationship relationship = relationship( relationshipId );
        Transaction tx = graphDb.beginTx();
        try
        {
            clear( relationship );
            tx.success();
        } finally
        {
            tx.finish();
        }
    }

    public void removeRelationshipProperty( long relationshipId, String key )
            throws RelationshipNotFoundException, NoSuchPropertyException
    {
        Relationship relationship = relationship( relationshipId );
        Transaction tx = graphDb.beginTx();
        try
        {
            if ( relationship.removeProperty( key ) == null )
            {
                throw new NoSuchPropertyException( relationship, key );
            }
            tx.success();
        } finally
        {
            tx.finish();
        }
    }

    // Index

    public enum IndexType
    {
        node( "index" )
                {
                },
        relationship( "index" )
                {
                };
        private final String pathPrefix;

        private IndexType( String pathPrefix )
        {
            this.pathPrefix = pathPrefix;
        }

        @SuppressWarnings( "boxing" )
        String path( String indexName, String key, String value, long id )
        {
            return String.format( "%s/%s/%s/%s/%s", pathPrefix, indexName, key, value, id );
        }
    }

    public Representation nodeIndexRoot()
    {
        return new NodeIndexRootRepresentation( graphDb.index() );
    }

    public Representation relationshipIndexRoot()
    {
        return new RelationshipIndexRootRepresentation( graphDb.index() );
    }

    public IndexedEntityRepresentation addToRelationshipIndex( String indexName, String key,
                                                               String value, long relationshipId )
    {
        Transaction tx = graphDb.beginTx();
        try
        {
            Relationship relationship = graphDb.getRelationshipById( relationshipId );
            Index<Relationship> index = graphDb.index().forRelationships( indexName );
            index.add( relationship, key, value );
            tx.success();
            return new IndexedEntityRepresentation( relationship, key, value, new RelationshipIndexRepresentation( indexName, Collections.EMPTY_MAP ) );
        } finally
        {
            tx.finish();
        }
    }

    public IndexedEntityRepresentation addToNodeIndex( String indexName, String key,
                                                       String value, long nodeId )
    {
        Transaction tx = graphDb.beginTx();
        try
        {
            Node node = graphDb.getNodeById( nodeId );
            Index<Node> index = graphDb.index().forNodes( indexName );
            index.add( node, key, value );
            tx.success();
            return new IndexedEntityRepresentation( node, key, value, new NodeIndexRepresentation( indexName, Collections.EMPTY_MAP ) );
        } finally
        {
            tx.finish();
        }
    }

    public void removeFromIndex( IndexType type, String indexName, String key, String value, long id )
    {
        // TODO tobias: Implement removeFromIndex() [Dec 13, 2010]
        throw new UnsupportedOperationException(
                "Not implemented: DatabaseActions.removeFromIndex()" );
    }

    public IndexedEntityRepresentation getIndexedNode( String indexName,
                                                       String key, String value, long id )
    {
        Node node = graphDb.getNodeById( id );
        return new IndexedEntityRepresentation( node, key, value, new NodeIndexRepresentation( indexName, Collections.EMPTY_MAP ) );
    }

    public IndexedEntityRepresentation getIndexedRelationship( String indexName,
                                                               String key, String value, long id )
    {
        Relationship node = graphDb.getRelationshipById( id );
        return new IndexedEntityRepresentation( node, key, value, new RelationshipIndexRepresentation( indexName, Collections.EMPTY_MAP ) );
    }

    public ListRepresentation getIndexedNodes( String indexName, String key,
                                               String value )
    {
        List<IndexedEntityRepresentation> representations = new ArrayList<IndexedEntityRepresentation>();
        Index<Node> index = graphDb.index().forNodes( indexName );

        Transaction tx = graphDb.beginTx();
        try
        {
            IndexRepresentation indexRepresentation = new NodeIndexRepresentation( indexName );
            for ( Node node : index.get( key, value ) )
            {
                representations.add( new IndexedEntityRepresentation( node, key, value,  indexRepresentation ));
            }
            tx.success();
            return new ListRepresentation( "nodes", representations );
        } finally
        {
            tx.finish();
        }
    }


    public ListRepresentation getIndexedRelationships( String indexName, String key,
                                                       String value )
    {
        List<IndexedEntityRepresentation> representations = new ArrayList<IndexedEntityRepresentation>();
        Index<Relationship> index = graphDb.index().forRelationships( indexName );

        Transaction tx = graphDb.beginTx();
        try
        {
            IndexRepresentation indexRepresentation = new NodeIndexRepresentation( indexName );
            for ( Relationship node : index.get( key, value ) )
            {
                representations.add( new IndexedEntityRepresentation( node, key, value,  indexRepresentation ));
            }
            tx.success();
            return new ListRepresentation( "relationships", representations );
        } finally
        {
            tx.finish();
        }
    }

    // Traversal

    public ListRepresentation traverse( long startNode, Map<String, Object> description,
                                        TraverserReturnType returnType )
    {
        Node node = graphDb.getNodeById( startNode );

        List<Representation> result = new ArrayList<Representation>();

        TraversalDescription traversalDescription = TraversalDescriptionBuilder.from( description );
        for ( Path position : traversalDescription.traverse( node ) )
        {
            switch ( returnType )
            {
                case node:
                    result.add( new NodeRepresentation( position.endNode() ) );
                    break;
                case relationship:
                    result.add( new RelationshipRepresentation( position.lastRelationship() ) );
                    break;
                case path:
                    result.add( new PathRepresentation( position ) );
                    break;
            }
        }

        return new ListRepresentation( "traversal-result", result );
    }

    public PathRepresentation findSinglePath( long startId, long endId,
                                              Map<String, Object> map )
    {
        FindParams findParams = new FindParams( startId, endId, map ).invoke();
        PathFinder<Path> finder = findParams.getFinder();
        Node startNode = findParams.getStartNode();
        Node endNode = findParams.getEndNode();

        Path path = finder.findSinglePath( startNode, endNode );
        return new PathRepresentation( path );
    }

    public ListRepresentation findPaths( long startId, long endId,
                                         Map<String, Object> map )
    {
        FindParams findParams = new FindParams( startId, endId, map ).invoke();
        PathFinder<Path> finder = findParams.getFinder();
        Node startNode = findParams.getStartNode();
        Node endNode = findParams.getEndNode();

        Iterable<Path> paths = finder.findAllPaths( startNode, endNode );

        IterableWrapper<PathRepresentation, Path> pathRepresentations = new IterableWrapper<PathRepresentation, Path>( paths )
        {
            @Override
            protected PathRepresentation underlyingObjectToObject( final Path path )
            {
                return new PathRepresentation( path );
            }
        };

        return new ListRepresentation( "paths", pathRepresentations );
    }

    // Extensions

    public Representation extensionsList()
    {
        // TODO tobias: Implement extensionsList() [Dec 14, 2010]
        throw new UnsupportedOperationException(
                "Not implemented: DatabaseActions.extensionsList()" );
    }

    public Representation getExtensionDetails( String extensionName )
    {
        // TODO tobias: Implement getExtensionDetails() [Dec 14, 2010]
        throw new UnsupportedOperationException(
                "Not implemented: DatabaseActions.getExtensionDetails()" );
    }

    public Representation invokeGraphDatabaseExtension( String extensionName, String method )
    {
        // TODO tobias: Implement invokeGraphDatabaseExtension() [Dec 14, 2010]
        throw new UnsupportedOperationException(
                "Not implemented: DatabaseActions.invokeGraphDatabaseExtension()" );
    }

    public Representation invokeNodeExtension( long nodeId, String extensionName, String method )
            throws NodeNotFoundException
    {
        Node node = node( nodeId );
        // TODO tobias: Implement invokeNodeExtension() [Dec 14, 2010]
        throw new UnsupportedOperationException(
                "Not implemented: DatabaseActions.invokeNodeExtension()" );
    }

    public Representation invokeRelationshipExtension( long relationshipId, String extensionName,
                                                       String method ) throws RelationshipNotFoundException
    {
        Relationship relationship = relationship( relationshipId );
        // TODO tobias: Implement invokeRelationshipExtension() [Dec 14, 2010]
        throw new UnsupportedOperationException(
                "Not implemented: DatabaseActions.invokeRelationshipExtension()" );
    }

    private class FindParams
    {
        private final long startId;
        private final long endId;
        private final Map<String, Object> map;
        private Node startNode;
        private Node endNode;
        private PathFinder<Path> finder;

        public FindParams( final long startId, final long endId, final Map<String, Object> map )
        {
            this.startId = startId;
            this.endId = endId;
            this.map = map;
        }

        public Node getStartNode()
        {
            return startNode;
        }

        public Node getEndNode()
        {
            return endNode;
        }

        public PathFinder<Path> getFinder()
        {
            return finder;
        }

        public FindParams invoke()
        {
            startNode = graphDb.getNodeById( startId );
            endNode = graphDb.getNodeById( endId );

            Integer maxDepthObj = (Integer)map.get( "max depth" );
            int maxDepth = ( maxDepthObj != null ) ? maxDepthObj : 1;

            RelationshipExpander expander = RelationshipExpanderBuilder.describeRelationships( map );

            String algorithm = (String)map.get( "algorithm" );
            algorithm = ( algorithm != null ) ? algorithm : "shortestPath";

            finder = getAlgorithm( algorithm, expander, maxDepth );
            return this;
        }

        private PathFinder<Path> getAlgorithm( String algorithm, RelationshipExpander expander, int maxDepth )
        {
            if ( algorithm.equals( "shortestPath" ) )
            {
                return GraphAlgoFactory.shortestPath( expander, maxDepth );
            } else if ( algorithm.equals( "allSimplePaths" ) )
            {
                return GraphAlgoFactory.allSimplePaths( expander, maxDepth );
            } else if ( algorithm.equals( "allPaths" ) )
            {
                return GraphAlgoFactory.allPaths( expander, maxDepth );
            }

            throw new RuntimeException( "Failed to find matching algorithm" );
        }
    }
}
