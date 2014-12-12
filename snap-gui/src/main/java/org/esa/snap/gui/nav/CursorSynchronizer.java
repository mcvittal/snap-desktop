/*
 * Copyright (C) 2010 Brockmann Consult GmbH (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

package org.esa.snap.gui.nav;

import com.bc.ceres.glayer.support.ImageLayer;
import org.esa.beam.framework.datamodel.GeoCoding;
import org.esa.beam.framework.datamodel.GeoPos;
import org.esa.beam.framework.datamodel.PixelPos;
import org.esa.beam.framework.ui.PixelPositionListener;
import org.esa.beam.framework.ui.product.ProductSceneView;
import org.esa.snap.gui.util.WindowUtilities;
import org.esa.snap.gui.windows.ProductSceneViewTopComponent;
import org.openide.windows.TopComponent;

import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.HashMap;
import java.util.Map;

public class CursorSynchronizer {

    private static final GeoPos INVALID_GEO_POS = new GeoPos(Float.NaN, Float.NaN);

    private final Map<ProductSceneView, CursorOverlay> psvOverlayMap;
    private final Map<ProductSceneView, ViewPPL> viewPplMap;
    private PsvListUpdater psvOverlayMapUpdater;
    private boolean enabled;

    public CursorSynchronizer() {
        psvOverlayMap = new HashMap<>();
        viewPplMap = new HashMap<>();
        psvOverlayMapUpdater = new PsvListUpdater();
        enabled = false;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            if (enabled) {
                initPsvOverlayMap();
                WindowUtilities.addListener(psvOverlayMapUpdater);
            } else {
                WindowUtilities.removeListener(psvOverlayMapUpdater);
                clearPsvOverlayMap();
            }
            this.enabled = enabled;
        }
    }

    public void updateCursorOverlays(GeoPos geoPos) {
        updateCursorOverlays(geoPos, null);
    }

    public void updateCursorOverlays(GeoPos geoPos, ProductSceneView sourceView) {
        if (!isEnabled()) {
            return;
        }
        for (Map.Entry<ProductSceneView, CursorOverlay> entry : psvOverlayMap.entrySet()) {
            final ProductSceneView view = entry.getKey();
            CursorOverlay overlay = entry.getValue();
            if (overlay == null) {
                if (view != sourceView) {
                    overlay = new CursorOverlay(view, geoPos);
                    psvOverlayMap.put(view, overlay);
                    view.getLayerCanvas().addOverlay(overlay);
                }
            } else {
                if (view != sourceView) {
                    overlay.setGeoPosition(geoPos);
                    view.getLayerCanvas().repaint();
                } else {
                    view.getLayerCanvas().removeOverlay(overlay);
                    psvOverlayMap.put(view, null);
                }
            }
        }
    }

    private void initPsvOverlayMap() {
        java.util.List<ProductSceneView> productSceneViews = WindowUtilities.collectOpen(ProductSceneViewTopComponent.class, new WindowUtilities.Converter<ProductSceneViewTopComponent, ProductSceneView>() {
            @Override
            protected ProductSceneView convert(ProductSceneViewTopComponent topComponent) {
                return topComponent.getView();
            }
        });
        for (ProductSceneView productSceneView : productSceneViews) {
            addPPL(productSceneView);
        }
    }

    private void clearPsvOverlayMap() {
        for (Map.Entry<ProductSceneView, CursorOverlay> entry : psvOverlayMap.entrySet()) {
            final ProductSceneView view = entry.getKey();
            removePPL(view);
            view.getLayerCanvas().removeOverlay(entry.getValue());
        }
        psvOverlayMap.clear();
    }

    private void addPPL(ProductSceneView view) {
        GeoCoding geoCoding = view.getProduct().getGeoCoding();
        if (geoCoding != null && geoCoding.canGetPixelPos()) {
            psvOverlayMap.put(view, null);
            ViewPPL ppl = new ViewPPL(view);
            viewPplMap.put(view, ppl);
            view.addPixelPositionListener(ppl);
        }
    }

    private void removePPL(ProductSceneView view) {
        GeoCoding geoCoding = view.getProduct().getGeoCoding();
        if (geoCoding != null && geoCoding.canGetPixelPos()) {
            ViewPPL ppl = viewPplMap.get(view);
            viewPplMap.remove(view);
            view.removePixelPositionListener(ppl);
        }
    }

    private class PsvListUpdater implements WindowUtilities.Listener {

        @Override
        public void windowOpened(WindowUtilities.Event e) {
            TopComponent topComponent = e.getTopComponent();
            if (topComponent instanceof ProductSceneViewTopComponent) {
                ProductSceneView view = ((ProductSceneViewTopComponent) topComponent).getView();
                addPPL(view);
            }
        }

        @Override
        public void windowClosed(WindowUtilities.Event e) {
            TopComponent topComponent = e.getTopComponent();
            if (topComponent instanceof ProductSceneViewTopComponent) {
                ProductSceneView view = ((ProductSceneViewTopComponent) topComponent).getView();
                removePPL(view);
            }
        }

        @Override
        public void windowActivated(WindowUtilities.Event e) {
        }

        @Override
        public void windowDeactivated(WindowUtilities.Event e) {
        }
    }

    private class ViewPPL implements PixelPositionListener {

        private final ProductSceneView view;

        private ViewPPL(ProductSceneView view) {
            this.view = view;
        }

        @Override
        public void pixelPosChanged(ImageLayer baseImageLayer, int pixelX, int pixelY, int currentLevel,
                                    boolean pixelPosValid, MouseEvent e) {
            PixelPos pixelPos = computeLevelZeroPixelPos(baseImageLayer, pixelX, pixelY, currentLevel);
            GeoPos geoPos = view.getRaster().getGeoCoding().getGeoPos(pixelPos, null);
            updateCursorOverlays(geoPos, view);
        }

        private PixelPos computeLevelZeroPixelPos(ImageLayer imageLayer, int pixelX, int pixelY, int currentLevel) {
            if (currentLevel != 0) {
                AffineTransform i2mTransform = imageLayer.getImageToModelTransform(currentLevel);
                Point2D modelP = i2mTransform.transform(new Point2D.Double(pixelX + 0.5, pixelY + 0.5), null);
                AffineTransform m2iTransform = imageLayer.getModelToImageTransform();
                Point2D imageP = m2iTransform.transform(modelP, null);

                return new PixelPos(new Float(imageP.getX()), new Float(imageP.getY()));
            } else {
                return new PixelPos(pixelX + 0.5f, pixelY + 0.5f);
            }
        }

        @Override
        public void pixelPosNotAvailable() {
            updateCursorOverlays(INVALID_GEO_POS, null);
        }
    }
}