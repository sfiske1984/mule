/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.routing.outbound;

import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.api.endpoint.EndpointException;
import org.mule.api.endpoint.EndpointURI;
import org.mule.api.endpoint.ImmutableEndpoint;
import org.mule.api.endpoint.OutboundEndpoint;
import org.mule.api.expression.ExpressionManager;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.api.processor.MessageProcessor;
import org.mule.api.routing.CouldNotRouteOutboundMessageException;
import org.mule.api.routing.RoutePathNotFoundException;
import org.mule.api.routing.RoutingException;
import org.mule.api.routing.TransformingMatchable;
import org.mule.api.routing.filter.Filter;
import org.mule.api.transformer.Transformer;
import org.mule.config.i18n.CoreMessages;
import org.mule.endpoint.DynamicURIOutboundEndpoint;
import org.mule.endpoint.MuleEndpointURI;
import org.mule.util.TemplateParser;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * <code>FilteringRouter</code> is a router that accepts events based on a filter
 * set.
 */

public class FilteringOutboundRouter extends AbstractOutboundRouter implements TransformingMatchable
{
    protected ExpressionManager expressionManager;

    private List<Transformer> transformers = new LinkedList<Transformer>();

    private Filter filter;

    private boolean useTemplates = true;
    
    // We used Square templates as they can exist as part of an URI.
    private TemplateParser parser = TemplateParser.createSquareBracesStyleParser();

    @Override
    public void initialise() throws InitialisationException
    {
        super.initialise();
        expressionManager = muleContext.getExpressionManager();
    }

    @Override
    public MuleEvent route(MuleEvent event) throws RoutingException
    {
        MuleEvent result;

        MuleMessage message = event.getMessage();

        if (routes == null || routes.size() == 0)
        {
            throw new RoutePathNotFoundException(CoreMessages.noEndpointsForRouter(), event, null);
        }

        MessageProcessor ep = getRoute(0, message);

        try
        {
            result = sendRequest(event, message, ep, true);
        }
        catch (RoutingException e)
        {
            throw e;
        }
        catch (MuleException e)
        {
            throw new CouldNotRouteOutboundMessageException(event, ep, e);
        }
        return result;
    }

    public Filter getFilter()
    {
        return filter;
    }

    public void setFilter(Filter filter)
    {
        this.filter = filter;
    }

    public boolean isMatch(MuleMessage message) throws RoutingException
    {
        if (getFilter() == null)
        {
            return true;
        }
        
        try
        {
            message.applyTransformers(null, transformers);
        }
        catch (MuleException e)
        {
            throw new RoutingException(CoreMessages.transformFailedBeforeFilter(), message, 
                routes.get(0), e);
        }
        
        return getFilter().accept(message);
    }

    public List<Transformer> getTransformers()
    {
        return transformers;
    }

    public void setTransformers(List<Transformer> transformers)
    {
        this.transformers = transformers;
    }

    @Override
    public void addRoute(MessageProcessor target)
    {
        if (!useTemplates)
        {
            if (target instanceof ImmutableEndpoint)
            {
                ImmutableEndpoint endpoint = (ImmutableEndpoint) target;
                if (parser.isContainsTemplate(endpoint.getEndpointURI().toString()))
                {
                    useTemplates = true;
                }
            }
        }
        super.addRoute(target);
    }

    /**
     * Will Return the target at the given index and will resolve any template tags
     * on the Endpoint URI if necessary
     * 
     * @param index the index of the endpoint to get
     * @param message the current message. This is required if template matching is
     *            being used
     * @return the endpoint at the index, with any template tags resolved
     * @throws CouldNotRouteOutboundMessageException if the template causs the
     *             endpoint to become illegal or malformed
     */
    public MessageProcessor getRoute(int index, MuleMessage message)
        throws CouldNotRouteOutboundMessageException
    {
        if (!useTemplates)
        {
            return routes.get(index);
        }
        else
        {
            MessageProcessor mp = routes.get(index);
            if (!(mp instanceof ImmutableEndpoint))
            {
                return routes.get(index);
            }
            OutboundEndpoint ep = (OutboundEndpoint) mp;
            String uri = ep.getAddress();

            if (logger.isDebugEnabled())
            {
                logger.debug("Uri before parsing is: " + uri);
            }

            Map props = new HashMap();
            // Also add the endpoint properties so that users can set fallback values
            // when the property is not set on the event
            props.putAll(ep.getProperties());
            for (String propertyKey : message.getOutboundPropertyNames())
            {
                Object value = message.getOutboundProperty(propertyKey);
                props.put(propertyKey, value);
            }

            propagateMagicProperties(message, message);

            if (!parser.isContainsTemplate(uri))
            {
                logger.debug("Uri does not contain template(s)");
                return ep;
            }
            else
            {

                String newUriString = parser.parse(props, uri);
                if (parser.isContainsTemplate(newUriString))
                {
                    newUriString = this.getMuleContext().getExpressionManager().parse(newUriString, message, true);
                }
                if (logger.isDebugEnabled())
                {
                    logger.debug("Uri after parsing is: " + uri);
                }
                try
                {
                    EndpointURI newUri = new MuleEndpointURI(newUriString, muleContext);
                    EndpointURI endpointURI = ep.getEndpointURI();
                    if (endpointURI != null && !newUri.getScheme().equalsIgnoreCase(endpointURI.getScheme()))
                    {
                        throw new CouldNotRouteOutboundMessageException(
                            CoreMessages.schemeCannotChangeForRouter(ep.getEndpointURI().getScheme(),
                                newUri.getScheme()), message, ep);
                    }

                    return new DynamicURIOutboundEndpoint(ep, newUri);
                }
                catch (EndpointException e)
                {
                    throw new CouldNotRouteOutboundMessageException(
                        CoreMessages.templateCausedMalformedEndpoint(uri, newUriString), message, ep, e);
                }
            }
        }
    }

    public boolean isUseTemplates()
    {
        return useTemplates;
    }

    public void setUseTemplates(boolean useTemplates)
    {
        this.useTemplates = useTemplates;
    }
    
    public boolean isTransformBeforeMatch()
    {
        return !transformers.isEmpty();
    }

}
