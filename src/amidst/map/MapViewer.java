package amidst.map;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

import amidst.logging.Log;
import amidst.map.object.MapObjectPlayer;
import amidst.map.widget.BiomeToggleWidget;
import amidst.map.widget.BiomeWidget;
import amidst.map.widget.CursorInformationWidget;
import amidst.map.widget.DebugWidget;
import amidst.map.widget.FpsWidget;
import amidst.map.widget.PanelWidget.CornerAnchorPoint;
import amidst.map.widget.ScaleWidget;
import amidst.map.widget.SeedWidget;
import amidst.map.widget.SelectedObjectWidget;
import amidst.map.widget.Widget;
import amidst.minecraft.MinecraftUtil;
import amidst.minecraft.world.World;
import amidst.resources.ResourceLoader;

public class MapViewer {
	private class Listeners implements MouseListener, MouseWheelListener {
		private Widget mouseOwner;

		@Override
		public void mouseWheelMoved(MouseWheelEvent e) {
			int notches = e.getWheelRotation();
			Point mouse = getMousePositionFromEvent(e);
			Widget widget = findWidget(mouse);
			if (widget != null
					&& widget.onMouseWheelMoved(
							translateMouseXCoordinateToWidget(mouse, widget),
							translateMouseYCoordinateToWidget(mouse, widget),
							notches)) {
				// noop
			} else {
				zoom.adjustZoom(mouse, notches);
			}
		}

		@Override
		public void mouseClicked(MouseEvent e) {
			if (e.isMetaDown()) {
				return;
			}
			Point mouse = getMousePositionFromEvent(e);
			Widget widget = findWidget(mouse);
			if (widget != null
					&& widget.onClick(
							translateMouseXCoordinateToWidget(mouse, widget),
							translateMouseYCoordinateToWidget(mouse, widget))) {
				// noop
			} else {
				mouseClickedOnMap(mouse);
			}
		}

		@Override
		public void mousePressed(MouseEvent e) {
			Point mouse = getMousePositionFromEvent(e);
			if (e.isPopupTrigger()) {
				showMenu(e);
			} else if (e.isMetaDown()) {
			} else if (mousePressedOnWidget(e, mouse)) {
			} else {
				lastMouse = mouse;
			}
		}

		private boolean mousePressedOnWidget(MouseEvent e, Point mouse) {
			Widget widget = findWidget(mouse);
			if (widget != null
					&& widget.onMousePressed(
							translateMouseXCoordinateToWidget(mouse, widget),
							translateMouseYCoordinateToWidget(mouse, widget))) {
				mouseOwner = widget;
				return true;
			} else {
				return false;
			}
		}

		@Override
		public void mouseReleased(MouseEvent e) {
			if (e.isPopupTrigger()) {
				showMenu(e);
			} else if (mouseOwner != null) {
				mouseOwner.onMouseReleased();
				mouseOwner = null;
			} else {
				lastMouse = null;
			}
		}

		@Override
		public void mouseEntered(MouseEvent e) {
		}

		@Override
		public void mouseExited(MouseEvent e) {
		}

		private void showMenu(MouseEvent e) {
			if (MinecraftUtil.getVersion().saveEnabled() && world.isFileWorld()) {
				createPlayerMenu(getMousePositionFromEvent(e)).show(
						e.getComponent(), e.getX(), e.getY());
			}
		}

		private JPopupMenu createPlayerMenu(Point lastRightClicked) {
			JPopupMenu result = new JPopupMenu();
			for (MapObjectPlayer player : layerContainer.getPlayerLayer()
					.getPlayers()) {
				result.add(createPlayerMenuItem(player, lastRightClicked));
			}
			return result;
		}

		private JMenuItem createPlayerMenuItem(final MapObjectPlayer player,
				final Point lastRightClick) {
			JMenuItem result = new JMenuItem(player.getName());
			result.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					Point location = map.screenToLocal(lastRightClick);
					player.setPosition(location.x, location.y);
					player.setFragment(map.getFragmentAt(location));
				}
			});
			return result;
		}

		private void mouseClickedOnMap(Point mouse) {
			map.setSelectedMapObject(map.getObjectAt(mouse, 50.0));
		}

		/**
		 * Don't use getMousePosition() of the JComponent in mouse events,
		 * because when computer is swapping/grinding, mouse may have moved out
		 * of window before execution reaches here.
		 */
		private Point getMousePositionFromEvent(MouseEvent e) {
			return e.getPoint();
		}

		private Widget findWidget(Point mouse) {
			for (Widget widget : widgets) {
				if (widget.isVisible() && isMouseInWidgetBounds(mouse, widget)) {
					return widget;
				}
			}
			return null;
		}

		private int translateMouseXCoordinateToWidget(Point mouse, Widget widget) {
			return mouse.x - widget.getX();
		}

		private int translateMouseYCoordinateToWidget(Point mouse, Widget widget) {
			return mouse.y - widget.getY();
		}

		private boolean isMouseInWidgetBounds(Point mouse, Widget widget) {
			return mouse.x > widget.getX() && mouse.y > widget.getY()
					&& mouse.x < widget.getX() + widget.getWidth()
					&& mouse.y < widget.getY() + widget.getHeight();
		}
	}

	@SuppressWarnings("serial")
	private class Component extends JComponent {
		private long lastTime = System.currentTimeMillis();

		@Override
		public void paint(Graphics g) {
			Graphics2D g2d = (Graphics2D) g.create();
			float time = calculateTimeSpanSinceLastDrawInSeconds();

			clear(g2d);

			updateMapZoom();
			updateMapMovement();

			setViewerDimensions();

			drawMap(g2d, time);
			drawBorder(g2d);
			drawWidgets(g2d, time);
		}

		private float calculateTimeSpanSinceLastDrawInSeconds() {
			long currentTime = System.currentTimeMillis();
			float result = Math.min(Math.max(0, currentTime - lastTime), 100) / 1000.0f;
			lastTime = currentTime;
			return result;
		}

		private void clear(Graphics2D g2d) {
			g2d.setColor(Color.black);
			g2d.fillRect(0, 0, this.getWidth(), this.getHeight());
		}

		private void updateMapZoom() {
			zoom.update(map);
		}

		private void updateMapMovement() {
			movement.update(map, lastMouse, component.getMousePosition());
		}

		private void setViewerDimensions() {
			map.setViewerWidth(getWidth());
			map.setViewerHeight(getHeight());
		}

		public void drawMap(Graphics2D g2d, float time) {
			g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
					RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			map.draw((Graphics2D) g2d.create(), time);
		}

		private void drawBorder(Graphics2D g2d) {
			int width10 = getWidth() - 10;
			int height10 = getHeight() - 10;
			int width20 = getWidth() - 20;
			int height20 = getHeight() - 20;
			g2d.drawImage(DROP_SHADOW_TOP_LEFT, 0, 0, null);
			g2d.drawImage(DROP_SHADOW_TOP_RIGHT, width10, 0, null);
			g2d.drawImage(DROP_SHADOW_BOTTOM_LEFT, 0, height10, null);
			g2d.drawImage(DROP_SHADOW_BOTTOM_RIGHT, width10, height10, null);
			g2d.drawImage(DROP_SHADOW_TOP, 10, 0, width20, 10, null);
			g2d.drawImage(DROP_SHADOW_BOTTOM, 10, height10, width20, 10, null);
			g2d.drawImage(DROP_SHADOW_LEFT, 0, 10, 10, height20, null);
			g2d.drawImage(DROP_SHADOW_RIGHT, width10, 10, 10, height20, null);
		}

		public void drawWidgets(Graphics2D g2d, float time) {
			g2d.setFont(textFont);
			for (Widget widget : widgets) {
				if (widget.isVisible()) {
					g2d.setComposite(AlphaComposite.getInstance(
							AlphaComposite.SRC_OVER, widget.getAlpha()));
					widget.draw(g2d, time);
				}
			}
		}

		public Point getMousePositionOrCenter() {
			Point result = component.getMousePosition();
			if (result == null) {
				result = new Point(component.getWidth() >> 1,
						component.getHeight() >> 1);
			}
			return result;
		}
	}

	private static final BufferedImage DROP_SHADOW_BOTTOM_LEFT = ResourceLoader
			.getImage("dropshadow/inner_bottom_left.png");
	private static final BufferedImage DROP_SHADOW_BOTTOM_RIGHT = ResourceLoader
			.getImage("dropshadow/inner_bottom_right.png");
	private static final BufferedImage DROP_SHADOW_TOP_LEFT = ResourceLoader
			.getImage("dropshadow/inner_top_left.png");
	private static final BufferedImage DROP_SHADOW_TOP_RIGHT = ResourceLoader
			.getImage("dropshadow/inner_top_right.png");
	private static final BufferedImage DROP_SHADOW_BOTTOM = ResourceLoader
			.getImage("dropshadow/inner_bottom.png");
	private static final BufferedImage DROP_SHADOW_TOP = ResourceLoader
			.getImage("dropshadow/inner_top.png");
	private static final BufferedImage DROP_SHADOW_LEFT = ResourceLoader
			.getImage("dropshadow/inner_left.png");
	private static final BufferedImage DROP_SHADOW_RIGHT = ResourceLoader
			.getImage("dropshadow/inner_right.png");

	private List<Widget> widgets = new ArrayList<Widget>();
	private Listeners listeners = new Listeners();
	private Component component = new Component();
	private MapMovement movement = new MapMovement();
	private JPanel panel = new JPanel();

	private MapZoom zoom;
	private World world;
	private LayerContainer layerContainer;

	private Map map;
	private Point lastMouse;

	private Font textFont = new Font("arial", Font.BOLD, 15);
	private FontMetrics textMetrics;

	public MapViewer(MapZoom zoom, World world, LayerContainer layerContainer,
			Map map) {
		this.zoom = zoom;
		this.world = world;
		this.layerContainer = layerContainer;
		this.map = map;
		initWidgets();
		initComponent();
		initPanel();
	}

	private void initWidgets() {
		widgets.add(new FpsWidget(this, CornerAnchorPoint.BOTTOM_LEFT));
		widgets.add(new ScaleWidget(this, CornerAnchorPoint.BOTTOM_CENTER));
		widgets.add(new SeedWidget(this, CornerAnchorPoint.TOP_LEFT));
		widgets.add(new DebugWidget(this, CornerAnchorPoint.BOTTOM_RIGHT));
		widgets.add(new SelectedObjectWidget(this, CornerAnchorPoint.TOP_LEFT));
		widgets.add(new CursorInformationWidget(this,
				CornerAnchorPoint.TOP_RIGHT));
		widgets.add(new BiomeToggleWidget(this, CornerAnchorPoint.BOTTOM_RIGHT));
		widgets.add(new BiomeWidget(this, CornerAnchorPoint.NONE));
	}

	private void initComponent() {
		component.addMouseListener(listeners);
		component.addMouseWheelListener(listeners);
		component.setFocusable(true);
		textMetrics = component.getFontMetrics(textFont);
	}

	private void initPanel() {
		panel.setBackground(Color.BLUE);
		panel.setLayout(new BorderLayout());
		panel.add(component, BorderLayout.CENTER);
	}

	public BufferedImage createCaptureImage() {
		BufferedImage image = new BufferedImage(component.getWidth(),
				component.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = image.createGraphics();
		component.drawMap(g2d, 0);
		component.drawWidgets(g2d, 0);
		g2d.dispose();
		return image;
	}

	public void dispose() {
		Log.debug("Disposing of map viewer.");
		map.dispose();
	}

	public void centerAt(long x, long y) {
		map.centerOn(x, y);
	}

	public Map getMap() {
		return map;
	}

	@Deprecated
	public FontMetrics getFontMetrics() {
		return textMetrics;
	}

	@Deprecated
	public FontMetrics getFontMetrics(Font font) {
		return component.getFontMetrics(font);
	}

	@Deprecated
	public Point getMousePosition() {
		return component.getMousePosition();
	}

	public int getWidth() {
		return component.getWidth();
	}

	public int getHeight() {
		return component.getHeight();
	}

	@Deprecated
	public void repaintImageLayers() {
		map.repaintImageLayers();
	}

	@Deprecated
	public Point getMousePositionOrCenter() {
		return component.getMousePositionOrCenter();
	}

	public void repaint() {
		component.repaint();
	}

	public JPanel getPanel() {
		return panel;
	}
}
