/*
 * ChunkIconsManager.java
 *
 * Copyright (C) 2009-12 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */
package org.rstudio.studio.client.workbench.views.source.editors.text;

import org.rstudio.core.client.dom.DomUtils;
import org.rstudio.core.client.theme.res.ThemeResources;
import org.rstudio.core.client.theme.res.ThemeStyles;
import org.rstudio.studio.client.RStudioGinjector;
import org.rstudio.studio.client.application.events.EventBus;
import org.rstudio.studio.client.workbench.commands.Commands;
import org.rstudio.studio.client.workbench.prefs.model.UIPrefs;
import org.rstudio.studio.client.workbench.views.console.shell.assist.PopupPositioner;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.AceEditorNative;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.DisplayChunkOptionsEvent;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.ExecuteChunkEvent;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.Position;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.Renderer;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.events.AfterAceRenderEvent;
import org.rstudio.studio.client.workbench.views.source.editors.text.themes.AceThemes;

import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Image;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class ChunkIconsManager
{
   public ChunkIconsManager()
   {
      RStudioGinjector.INSTANCE.injectMembers(this);
      events_.addHandler(
            AfterAceRenderEvent.TYPE,
            new AfterAceRenderEvent.Handler()
            {
               
               @Override
               public void onAfterAceRender(AfterAceRenderEvent event)
               {
                  manageChunkIcons(event.getEditor());
               }
            });
      optionsPanel_ = new ChunkOptionsPopupPanel();
   }
   
   @Inject
   private void initialize(EventBus events,
                           Commands commands,
                           UIPrefs uiPrefs,
                           AceThemes themes)
   {
      events_ = events;
      commands_ = commands;
      uiPrefs_ = uiPrefs;
      themes_ = themes;
   }
   
   private boolean isPseudoMarker(Element el)
   {
      return el.getOffsetHeight() == 0 || el.getOffsetWidth() == 0;
   }
   
   private boolean shouldDisplayIcons(AceEditorNative editor)
   {
      String id = editor.getSession().getMode().getId();
      return id.equals("mode/rmarkdown") || // also Rpres
             id.equals("mode/rhtml") ||
             id.equals("mode/sweave");
   }
   
   private void manageChunkIcons(AceEditorNative editor)
   {
      Element[] icons = DomUtils.getElementsByClassName(
            ThemeStyles.INSTANCE.inlineChunkToolbar());
      
      for (Element icon : icons)
         icon.removeFromParent();
      
      if (!uiPrefs_.showInlineToolbarForRCodeChunks().getValue())
         return;
      
      if (!shouldDisplayIcons(editor))
         return;
      
      Element[] chunkStarts = DomUtils.getElementsByClassName(
            "rstudio_chunk_start ace_start");
      
      for (int i = 0; i < chunkStarts.length; i++)
      {
         Element el = chunkStarts[i];
         
         if (isPseudoMarker(el))
            continue;
         
         if (el.getChildCount() > 0)
            el.removeAllChildren();
         
         addToolbar(el, isSetupChunk(el, editor));
      }
   }
   
   private boolean isSetupChunk(Element el, AceEditorNative editor)
   {
      int pageX = el.getAbsoluteLeft();
      int pageY = el.getAbsoluteTop();
      
      Position position =
            editor.getRenderer().screenToTextCoordinates(pageX, pageY);
      
      String line = editor.getSession().getLine(position.getRow());
      
      return line.contains("r setup");
   }
   
   private void addToolbar(Element el, boolean isSetupChunk)
   {
      FlowPanel toolbarPanel = new FlowPanel();
      toolbarPanel.addStyleName(ThemeStyles.INSTANCE.inlineChunkToolbar());
    
      boolean isDark = themes_.isDark(
            themes_.getEffectiveThemeName(uiPrefs_.theme().getValue()));
      
      if (isSetupChunk)
      {
         // TODO -- need a special icon / behaviour for setup chunk
         Image optionsIcon = createOptionsIcon(isDark);
         optionsIcon.getElement().getStyle().setMarginRight(5, Unit.PX);
         toolbarPanel.add(optionsIcon);
      }
      else
      {
         Image optionsIcon = createOptionsIcon(isDark);
         optionsIcon.getElement().getStyle().setMarginRight(5, Unit.PX);
         toolbarPanel.add(optionsIcon);

         Image runIcon = createRunIcon();
         toolbarPanel.add(runIcon);

      }
      
      display(toolbarPanel, el);
   }
   
   private void display(Widget panel, Element underlyingMarker)
   {
      // Bail if the underlying marker isn't wide enough
      if (underlyingMarker.getOffsetWidth() < 250)
         return;
      
      // Get the 'virtual' parent -- this is the Ace scroller that houses all
      // of the Ace content, where we want our icons to live. We need them
      // to live here so that they properly hide when the user scrolls and
      // e.g. markers are only partially visible.
      Element virtualParent = DomUtils.getParent(underlyingMarker, 3);
      
      // We'd prefer to use 'getOffsetTop()' here, but that seems to give
      // some janky dimensions due to how the Ace layers are ... layered,
      // so we manually compute it.
      int top =
            underlyingMarker.getAbsoluteTop() -
            virtualParent.getAbsoluteTop();
      
      panel.getElement().getStyle().setTop(top, Unit.PX);
      virtualParent.appendChild(panel.getElement());
   }
   
   private Image createRunIcon()
   {
      Image icon = new Image(ThemeResources.INSTANCE.runChunk());
      icon.addStyleName(ThemeStyles.INSTANCE.highlightIcon());
      icon.setTitle(commands_.executeCurrentChunk().getTooltip());
      bindNativeClickToExecuteChunk(this, icon.getElement());
      return icon;
   }
   
   private Image createOptionsIcon(boolean dark)
   {
      Image icon = new Image(dark ? 
            ThemeResources.INSTANCE.chunkOptionsDark() :
            ThemeResources.INSTANCE.chunkOptionsLight());
      icon.addStyleName(ThemeStyles.INSTANCE.highlightIcon());
      icon.setTitle("Modify chunk options");
      bindNativeClickToOpenOptions(this, icon.getElement());
      return icon;
   }
   
   private static final native void bindNativeClickToExecuteChunk(ChunkIconsManager manager,
                                                                  Element element) 
   /*-{
      element.addEventListener("click", function(evt) {
         manager.@org.rstudio.studio.client.workbench.views.source.editors.text.ChunkIconsManager::fireExecuteChunkEvent(Ljava/lang/Object;)(evt);
      });
   }-*/;
   
   private static final native void bindNativeClickToOpenOptions(ChunkIconsManager manager,
                                                                 Element element) 
   /*-{
      element.addEventListener("click", function(evt) {
         manager.@org.rstudio.studio.client.workbench.views.source.editors.text.ChunkIconsManager::fireDisplayChunkOptionsEvent(Ljava/lang/Object;)(evt);
      });
   }-*/;
   
   private final void fireExecuteChunkEvent(Object object)
   {
      if (!(object instanceof NativeEvent))
         return;
      
      NativeEvent event = (NativeEvent) object;
      events_.fireEvent(new ExecuteChunkEvent(event.getClientX(), event.getClientY()));
   }
   
   private final void fireDisplayChunkOptionsEvent(Object object)
   {
      if (!(object instanceof NativeEvent))
         return;
      
      NativeEvent event = (NativeEvent) object;
      events_.fireEvent(new DisplayChunkOptionsEvent(event));
   }
   
   public void displayChunkOptions(AceEditor editor, NativeEvent event)
   {
      // Translate the 'pageX' + 'pageY' position to document position
      int pageX = event.getClientX();
      int pageY = event.getClientY();
      
      Renderer renderer = editor.getWidget().getEditor().getRenderer();
      Position position = renderer.screenToTextCoordinates(pageX, pageY);
      optionsPanel_.init(editor.getWidget(), position);
      optionsPanel_.show();
      optionsPanel_.focus();
      PopupPositioner.setPopupPosition(
            optionsPanel_,
            pageX,
            pageY,
            10);
      
   }
   
   private final ChunkOptionsPopupPanel optionsPanel_;
   
   private Commands commands_;
   private EventBus events_;
   private UIPrefs uiPrefs_;
   private AceThemes themes_;

}
