/*****************************************************************************
 *                                                                           *
 *  This file is part of the BeanShell Java Scripting distribution.          *
 *  Documentation and updates may be found at http://www.beanshell.org/      *
 *                                                                           *
 *  Sun Public License Notice:                                               *
 *                                                                           *
 *  The contents of this file are subject to the Sun Public License Version  *
 *  1.0 (the "License"); you may not use this file except in compliance with *
 *  the License. A copy of the License is available at http://www.sun.com    * 
 *                                                                           *
 *  The Original Code is BeanShell. The Initial Developer of the Original    *
 *  Code is Pat Niemeyer. Portions created by Pat Niemeyer are Copyright     *
 *  (C) 2000.  All Rights Reserved.                                          *
 *                                                                           *
 *  GNU Public License Notice:                                               *
 *                                                                           *
 *  Alternatively, the contents of this file may be used under the terms of  *
 *  the GNU Lesser General Public License (the "LGPL"), in which case the    *
 *  provisions of LGPL are applicable instead of those above. If you wish to *
 *  allow use of your version of this file only under the  terms of the LGPL *
 *  and not to allow others to use your version of this file under the SPL,  *
 *  indicate your decision by deleting the provisions above and replace      *
 *  them with the notice and other provisions required by the LGPL.  If you  *
 *  do not delete the provisions above, a recipient may use your version of  *
 *  this file under either the SPL or the LGPL.                              *
 *                                                                           *
 *  Patrick Niemeyer (pat@pat.net)                                           *
 *  Author of Learning Java, O'Reilly & Associates                           *
 *  http://www.pat.net/~pat/                                                 *
 *                                                                           *
 *****************************************************************************/

package	bsh.util;

import java.awt.Component;
import java.awt.Font;
import java.awt.Color;
import java.awt.Insets;
import java.awt.event.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.awt.Cursor;

import javax.swing.text.*;
import javax.swing.*;

// Things that are not in the core packages

import bsh.EvalError;
import bsh.util.NameCompletion;

/**
	A JFC/Swing based console for the BeanShell desktop.
	This is a descendant of the old AWTConsole.

	Improvements by: Mark Donszelmann <Mark.Donszelmann@cern.ch>
		including Cut & Paste

  	Improvements by: Daniel Leuck
		including Color and Image support, key press bug workaround
*/
public class JConsole extends JScrollPane
	implements GUIConsoleInterface, Runnable, KeyListener,
	MouseListener, ActionListener, PropertyChangeListener 
{
	private static final long serialVersionUID = 1L;

	private final static String	CUT = "Cut";
    private final static String	COPY = "Copy";
    private final static String	PASTE =	"Paste";

	private	OutputStream outPipe;
	private	InputStream inPipe;
	private	InputStream in;
	private	PrintStream out;

	public InputStream getInputStream() { return in; }
	@Override
	public Reader getIn() { return new InputStreamReader(in); }
	@Override
	public PrintStream getOut() { return out;	}
	@Override
	public PrintStream getErr() { return out;	}

    private int	cmdStart = 0;
	private	Vector<String> history = new Vector<String>();
	private	String startedLine;
	private	int histLine = 0;

    private JPopupMenu menu;
    public JTextPane text;
    //private DefaultStyledDocument doc;

	NameCompletion nameCompletion;
	final int SHOW_AMBIG_MAX = 10;

	// hack to prevent key repeat for some reason?
    private boolean gotUp = true;

    
    
	public JConsole() {
		this(null, null);
	}
	public JConsole( InputStream cin, OutputStream cout )  
	{
		super();

		// Special TextPane which catches for cut and paste, both L&F keys and
		// programmatic	behaviour
		text = new JTextPane( new DefaultStyledDocument() ) 
			{
				private static final long serialVersionUID = 1L;

				@Override
				public void	cut() {
					if (text.getCaretPosition() < cmdStart)	{
						super.copy();
					} else {
						super.cut();
					}
				}

				@Override
				public void	paste()	{
					forceCaretMoveToEnd();
					super.paste();
				}
			};

		Font font = new	Font("Monospaced",Font.PLAIN,14);
		text.setText("");
		text.setFont( font );
		text.setMargin(	new Insets(7,5,7,5) );
		text.addKeyListener(this);
		setViewportView(text);

		// create popup	menu
		menu = new JPopupMenu("JConsole	Menu");
		menu.add(new JMenuItem(CUT)).addActionListener(this);
		menu.add(new JMenuItem(COPY)).addActionListener(this);
		menu.add(new JMenuItem(PASTE)).addActionListener(this);

		text.addMouseListener(this);

		// make	sure popup menu	follows	Look & Feel
		UIManager.addPropertyChangeListener(this);

		outPipe	= cout;
		if ( outPipe ==	null ) {
			outPipe	= new PipedOutputStream();
			try {
				in = new PipedInputStream((PipedOutputStream)outPipe);
			} catch	( IOException e	) {
				print("Console internal	error (1)...", Color.red);
			}
		}

		inPipe = cin;
		if ( inPipe == null ) {
			PipedOutputStream pout = new PipedOutputStream();
			out = new PrintStream( pout );
			try {
				inPipe = new BlockingPipedInputStream(pout);
			} catch ( IOException e ) { print("Console internal error: "+e); }
		}
		// Start the inpipe watcher
		new Thread( this ).start();

		requestFocus();
	}

	@Override
	public void requestFocus() 
	{
		super.requestFocus();
		text.requestFocus();
	}

	@Override
	public void keyPressed(	KeyEvent e ) {
	    type( e );
	    gotUp=false;
	}

	@Override
	public void keyTyped(KeyEvent e) {
	    type( e );
	}

    @Override
	public void	keyReleased(KeyEvent e)	{
		gotUp=true;
		type( e	);
    }

    private synchronized void type( KeyEvent e ) {
		switch ( e.getKeyCode()	) 
		{
			case ( KeyEvent.VK_ENTER ):
			    if (e.getID() == KeyEvent.KEY_PRESSED) {
					if (gotUp) {
						enter();
						resetCommandStart();
						text.setCaretPosition(cmdStart);
					}
				}
				e.consume();
				text.repaint();
				break;

			case ( KeyEvent.VK_UP ):
			    if (e.getID() == KeyEvent.KEY_PRESSED) {
				    historyUp();
				}
				e.consume();
				break;

			case ( KeyEvent.VK_DOWN	):
			    if (e.getID() == KeyEvent.KEY_PRESSED) {
					historyDown();
				}
				e.consume();
				break;

			case ( KeyEvent.VK_LEFT	):
			case ( KeyEvent.VK_BACK_SPACE ):
			case ( KeyEvent.VK_DELETE ):
				if (text.getCaretPosition() <= cmdStart) {
					// This doesn't work for backspace.
					// See default case for workaround
					e.consume();
				}
				break;

			case ( KeyEvent.VK_RIGHT ):
				forceCaretMoveToStart();
				break;

			case ( KeyEvent.VK_HOME ):
				text.setCaretPosition(cmdStart);
				e.consume();
				break;

			case ( KeyEvent.VK_U ):	// clear line
				if ( (e.getModifiers() & InputEvent.CTRL_MASK) > 0 ) {
					replaceRange( "", cmdStart, textLength());
					histLine = 0;
					e.consume();
				}
				break;

			case ( KeyEvent.VK_ALT ):
			case ( KeyEvent.VK_CAPS_LOCK ):
			case ( KeyEvent.VK_CONTROL ):
			case ( KeyEvent.VK_META ):
			case ( KeyEvent.VK_SHIFT ):
			case ( KeyEvent.VK_PRINTSCREEN ):
			case ( KeyEvent.VK_SCROLL_LOCK ):
			case ( KeyEvent.VK_PAUSE ):
			case ( KeyEvent.VK_INSERT ):
			case ( KeyEvent.VK_F1):
			case ( KeyEvent.VK_F2):
			case ( KeyEvent.VK_F3):
			case ( KeyEvent.VK_F4):
			case ( KeyEvent.VK_F5):
			case ( KeyEvent.VK_F6):
			case ( KeyEvent.VK_F7):
			case ( KeyEvent.VK_F8):
			case ( KeyEvent.VK_F9):
			case ( KeyEvent.VK_F10):
			case ( KeyEvent.VK_F11):
			case ( KeyEvent.VK_F12):
			case ( KeyEvent.VK_ESCAPE ):

			// only	modifier pressed
			break;

			// Control-C
			case ( KeyEvent.VK_C ):
				if (text.getSelectedText() == null) {
				    if (( (e.getModifiers() & InputEvent.CTRL_MASK) > 0	)
					&& (e.getID() == KeyEvent.KEY_PRESSED))	{
						append("^C");
					}
					e.consume();
				}
				break;

			case ( KeyEvent.VK_TAB ):
			    if (e.getID() == KeyEvent.KEY_RELEASED) {
					String part = text.getText().substring( cmdStart );
					doCommandCompletion( part );
				}
				e.consume();
				break;

			default:
				if ( 
					(e.getModifiers() & 
					(InputEvent.CTRL_MASK 
					| InputEvent.ALT_MASK | InputEvent.META_MASK)) == 0 ) 
				{
					// plain character
					forceCaretMoveToEnd();
				}

				/*
					The getKeyCode function always returns VK_UNDEFINED for
					keyTyped events, so backspace is not fully consumed.
				*/
				if (e.paramString().indexOf("Backspace") != -1)
				{ 
				  if (text.getCaretPosition() <= cmdStart) {
						e.consume();
						break;
					}
				}

				break;
		}
	}

	private void doCommandCompletion(final String part0 ) {
		if ( nameCompletion == null )
			return;

		int i=part0.length()-1;

		// Character.isJavaIdentifierPart()  How convenient for us!! 
		while (i >= 0 &&
				( Character.isJavaIdentifierPart(part0.charAt(i)))) { 
				//|| part0.charAt(i) == '.' )) { 
			i--;
		}
		
		String [] complete = new String[0];
		String part = null;
		
		if ( complete.length == 0 ) {
			java.awt.Toolkit.getDefaultToolkit().beep();
			return;
		}

		// Found one completion (possibly what we already have)
		if ( complete.length == 1 && !complete.equals(part) ) {
			String append = complete[0].substring(part.length());
			append( append );
			return;
		}

		// Found ambiguous, show (some of) them

		String line = text.getText();
		String command = line.substring( cmdStart );
		// Find prompt
		for(i=cmdStart; line.charAt(i) != '\n' && i > 0; i--);
		String prompt = line.substring( i+1, cmdStart );

		// Show ambiguous
		StringBuilder sb = new StringBuilder("\n");
		for( i=0; i<complete.length && i<SHOW_AMBIG_MAX; i++)
			sb.append( complete[i] +"\n" );
		if ( i == SHOW_AMBIG_MAX )
			sb.append("...\n");

		print( sb, Color.gray );
		print( prompt ); // print resets command start
		append( command ); // append does not reset command start
	}

	private void resetCommandStart() {
		cmdStart = textLength();
	}

	private	void append(String string) {
		int slen = textLength();
		text.select(slen, slen);
	    text.replaceSelection(string);
    }

    private String replaceRange(Object s, int start, int	end) {
		String st = s.toString();
		text.select(start, end);
	    text.replaceSelection(st);
	    //text.repaint();
	    return st;
    }

	private	void forceCaretMoveToEnd() {
		if (text.getCaretPosition() < cmdStart)	{
			// move caret first!
			text.setCaretPosition(textLength());
		}
		text.repaint();
    }

	private	void forceCaretMoveToStart() {
		if (text.getCaretPosition() < cmdStart)	{
			// move caret first!
		}
		text.repaint();
    }


	private	void enter() {
		String s = getCmd();

		if ( s.length()	== 0 )	// special hack	for empty return!
			s = ";\n";
		else {
			history.addElement( s );
			
			s = s +"\n";
		}

		append("\n");
		histLine = 0;
		acceptLine( s );
		text.repaint();
	}

    private String getCmd() {
		String s = "";
		try {
			s =	text.getText(cmdStart, textLength() - cmdStart);
		} catch	(BadLocationException e) {
			// should not happen
			System.out.println("Internal JConsole Error: "+e);
		}
		return s;
    }

	private	void historyUp() {
		if ( history.size() == 0 )
			return;
		if ( histLine == 0 )  // save current line
			startedLine = getCmd();
		if ( histLine <	history.size() ) {
			histLine++;
			showHistoryLine();
		}
	}
	
	private	void historyDown() {
		if ( histLine == 0 )
			return;

		histLine--;
		showHistoryLine();
	}

	private	void showHistoryLine() {
		String showline;
		if ( histLine == 0 )
			showline = startedLine;
		else
			showline = (String)history.elementAt( history.size() - histLine	);

		replaceRange( showline,	cmdStart, textLength() );
		text.setCaretPosition(textLength());
		text.repaint();
	}

	String ZEROS = "000";

	private	void acceptLine( String	line ) 
	{
		if (line.startsWith("??")) {
			line = line.trim();
			if (line.endsWith(";")) {
				line = line.substring(0, line.length()-1);
			}
			line = "javap(" + line.substring(2) +");\n";
		}
		if (line.startsWith("?")) {
			line = line.trim();
			if (line.endsWith(";")) {
				line = line.substring(0, line.length()-1);
			}
			line = "help(\"" + line.substring(1) +"\");\n";
		}
		// Patch to handle Unicode characters
		// Submitted by Daniel Leuck
		StringBuilder buf = new StringBuilder(); 
		int lineLength = line.length(); 
		for(int i=0; i<lineLength; i++) {  
				String val = Integer.toString(line.charAt(i), 16); 
				val=ZEROS.substring(0,4-val.length()) + val;
				buf.append("\\u" + val);
		} 
		line = buf.toString();
		// End unicode patch

		if (outPipe == null )
			print("Console internal	error: cannot output ...", Color.red);
		else
			try {
				byte [] bytes = line.getBytes();
				// RRB: following line can block the EventThread
				outPipe.write( bytes );
				outPipe.flush();
			} catch	( IOException e	) {
				outPipe	= null;
				throw new RuntimeException("Console pipe broken...");
			}
		//text.repaint();
	}

	@Override
	public void println(Object o) {
	    print( String.valueOf(o) + "\n" );
		text.repaint();
	}

	@Override
	public void print(final Object o) {
		invokeAndWait(new Runnable() {
			@Override
			public void run() {
				append(String.valueOf(o));
				resetCommandStart();
				text.setCaretPosition(cmdStart);
			}
		});
	}

	/**
	  * Prints "\\n" (i.e. newline)
	  */
	public void println() {
	    print("\n");
		text.repaint();
	}

	@Override
	public void error( Object o ) {
	    print( o, Color.red );
	}

	public void println(Icon icon) {
		print(icon);
		println();
		text.repaint();
	}

	public void print(final Icon icon) {
	    if (icon==null) 
			return;

		invokeAndWait(new Runnable() {
			@Override
			public void run() {
				text.insertIcon(icon);
				resetCommandStart();
				text.setCaretPosition(cmdStart);
			}
		});			
	}

	public void print(Object s, Font font) {
		print(s, font, null);
    }

	@Override
	public void print(Object s, Color color) {
		print(s, null, color);
	}

	public void print(final Object o, final Font font, final Color color) {
		invokeAndWait(new Runnable() {
			@Override
			public void run() {
				AttributeSet old = getStyle();
				setStyle(font, color);
				append(String.valueOf(o));
				resetCommandStart();
				text.setCaretPosition(cmdStart);
				setStyle(old, true);
			}
		});	
    }

	public void print(
	    Object s,
	    String fontFamilyName,
	    int	size,
	    Color color
	    ) {
			
	    print(s,fontFamilyName,size,color,false,false,false);
    }

	public void print(
	    final Object o,
	    final String fontFamilyName,
	    final int	size,
	    final Color color,
	    final boolean bold,
	    final  boolean italic,
	    final boolean underline
	    ) 
	{
		invokeAndWait(new Runnable() {
			@Override
			public void run() {
				AttributeSet old = getStyle();
				setStyle(fontFamilyName, size, color, bold,	italic,	underline);
				append(String.valueOf(o));
				resetCommandStart();
				text.setCaretPosition(cmdStart);
				setStyle(old, true);
			}
		});			
    }

//    private AttributeSet setStyle(Font font) {
//	    return setStyle(font, null);
//    }
//
//    private AttributeSet setStyle(Color color) {
//	    return setStyle(null, color);
//    }

    private AttributeSet setStyle( Font font, Color color) 
	{
	    if (font!=null)
			return setStyle( font.getFamily(), font.getSize(), color, 
				font.isBold(), font.isItalic(), 
				StyleConstants.isUnderline(getStyle()) );
		else
			return setStyle(null,-1,color);
    }

    private AttributeSet setStyle (
	    String fontFamilyName, int	size, Color color) 
	{
		MutableAttributeSet attr = new SimpleAttributeSet();
		if (color!=null)
			StyleConstants.setForeground(attr, color);
		if (fontFamilyName!=null)
			StyleConstants.setFontFamily(attr, fontFamilyName);
		if (size!=-1)
			StyleConstants.setFontSize(attr, size);

		setStyle(attr);

		return getStyle();
    }

    private AttributeSet setStyle(
	    String fontFamilyName,
	    int	size,
	    Color color,
	    boolean bold,
	    boolean italic,
	    boolean underline
	    ) 
	{
		MutableAttributeSet attr = new SimpleAttributeSet();
		if (color!=null)
			StyleConstants.setForeground(attr, color);
		if (fontFamilyName!=null)
			StyleConstants.setFontFamily(attr, fontFamilyName);
		if (size!=-1)
			StyleConstants.setFontSize(attr, size);
		StyleConstants.setBold(attr, bold);
		StyleConstants.setItalic(attr, italic);
		StyleConstants.setUnderline(attr, underline);

		setStyle(attr);

		return getStyle();
    }

    private void setStyle(AttributeSet attributes) {
		setStyle(attributes, false);
    }

    private void setStyle(AttributeSet attributes, boolean overWrite) {
		text.setCharacterAttributes(attributes,	overWrite);
    }

    private AttributeSet getStyle() {
		return text.getCharacterAttributes();
    }

	@Override
	public void setFont( Font font ) {
		super.setFont( font );

		if ( text != null )
			text.setFont( font );
	}

	private	void inPipeWatcher() throws IOException	{
		byte []	ba = new byte [256]; //	arbitrary blocking factor
		int read;
		while (	(read =	inPipe.read(ba)) != -1 ) {
			print( new String(ba, 0, read) );
			//text.repaint();
		}

		println("Console: Input	closed...");
	}

	@Override
	public void run() {
		try {
			inPipeWatcher();
		} catch	( IOException e	) {
			print("Console: I/O Error: "+e+"\n", Color.red);
		}
	}

	@Override
	public String toString() {
		return "BeanShell console";
	}

    // MouseListener Interface
    @Override
	public void	mouseClicked(MouseEvent	event) {
    }

    @Override
	public void mousePressed(MouseEvent event) {
        if (event.isPopupTrigger()) {
            menu.show(
				(Component)event.getSource(), event.getX(), event.getY());
        }
    }

    @Override
	public void	mouseReleased(MouseEvent event)	{
		if (event.isPopupTrigger()) {
			menu.show((Component)event.getSource(), event.getX(),
			event.getY());
		}
		text.repaint();
    }

    @Override
	public void	mouseEntered(MouseEvent	event) { }

    @Override
	public void	mouseExited(MouseEvent event) { }

    // property	change
    @Override
	public void	propertyChange(PropertyChangeEvent event) {
		if (event.getPropertyName().equals("lookAndFeel")) {
			SwingUtilities.updateComponentTreeUI(menu);
		}
    }

    // handle cut, copy	and paste
    @Override
	public void	actionPerformed(ActionEvent event) {
		String cmd = event.getActionCommand();
		if (cmd.equals(CUT)) {
			text.cut();
		} else if (cmd.equals(COPY)) {
			text.copy();
		} else if (cmd.equals(PASTE)) {
			text.paste();
		}
    }

	/**
	 * If not in the event thread run via SwingUtilities.invokeAndWait()
	 */
	private void invokeAndWait(Runnable run) {
//		if(!SwingUtilities.isEventDispatchThread()) {
//			try {
//				SwingUtilities.invokeAndWait(run);
//			} catch(Exception e) {
//				// shouldn't happen
//				e.printStackTrace();
//			}
//		} else {
			run.run();
//		}
	}

	/**
		The overridden read method in this class will not throw "Broken pipe"
		IOExceptions;  It will simply wait for new writers and data.
		This is used by the JConsole internal read thread to allow writers
		in different (and in particular ephemeral) threads to write to the pipe.

		It also checks a little more frequently than the original read().

		Warning: read() will not even error on a read to an explicitly closed 
		pipe (override closed to for that).
	*/
	public static class BlockingPipedInputStream extends PipedInputStream
	{
		boolean closed;
		public BlockingPipedInputStream( PipedOutputStream pout ) 
			throws IOException 
		{
			super(pout);
		}
		@Override
		public synchronized int read() throws IOException {
			if ( closed )
				throw new IOException("stream closed");

			while (super.in < 0) {	// While no data */
				notifyAll();	// Notify any writers to wake up
				try {
					wait(750);
				} catch ( InterruptedException e ) {
					throw new InterruptedIOException();
				}
			}
			// This is what the superclass does.
			int ret = buffer[super.out++] & 0xFF;
			if (super.out >= buffer.length)
				super.out = 0;
			if (super.in == super.out)
				super.in = -1;  /* now empty */
			return ret;
		}
		@Override
		public void close() throws IOException {
			closed = true;
			super.close();
		}
	}

	@Override
	public void setNameCompletion( NameCompletion nc ) {
		this.nameCompletion = nc;
	}

	@Override
	public void setWaitFeedback( boolean on ) {
		if ( on )
			setCursor( Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) );
		else
			setCursor( Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR) );
	}

	private int textLength() { return text.getDocument().getLength(); }

}


