/*
 * ProGuard -- shrinking, optimization, obfuscation, and preverification
 *             of Java bytecode.
 *
 * Copyright (c) 2002-2020 Guardsquare NV
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package com.virjar.tk.server.service.base.metric.mql.compile;


import java.io.File;
import java.io.IOException;
import java.io.LineNumberReader;
import java.util.LinkedList;


/**
 * An abstract reader of words, with the possibility to include other readers.
 * Words are separated by spaces or broken off at delimiters. Words containing
 * spaces or delimiters can be quoted with single or double quotes.
 * Comments (everything starting with '#' on a single line) are ignored.
 *
 * @author Eric Lafortune
 * @author iinti
 */
public class WordReader {
    private static final char COMMENT_CHARACTER = '#';
    private static final boolean printToken = false;

    private String currentLine;
    private int currentLineLength;
    private int currentIndex;
    private String currentWord;
    private final LinkedList<String> stageWords = new LinkedList<>();

    private String currentComments;
    private final LineNumberReader reader;
    private final String description;

    public WordReader(LineNumberReader reader, String description) {
        this.reader = reader;
        this.description = description;
    }

    public String nextWord() throws IOException {
        String ret = nextWordInternal();
        if (printToken) {
            System.out.println(ret);
        }
        return ret;
    }

    private String nextWordInternal() throws IOException {
        if (!stageWords.isEmpty()) {
            return stageWords.poll();
        }
        return nextWordImpl();
    }

    /**
     * Reads a word from this WordReader, or from one of its active included
     * WordReader objects.
     *
     * @return the read word.
     */
    private String nextWordImpl() throws IOException {
        currentWord = null;
        // Get a word from this reader.

        // Skip any whitespace and comments left on the current line.
        if (currentLine != null) {
            // Skip any leading whitespace.
            while (currentIndex < currentLineLength &&
                    Character.isWhitespace(currentLine.charAt(currentIndex))) {
                currentIndex++;
            }

            // Skip any comments.
            if (currentIndex < currentLineLength &&
                    isComment(currentLine.charAt(currentIndex))) {
                currentIndex = currentLineLength;
            }
        }

        // Make sure we have a non-blank line.
        while (currentLine == null || currentIndex == currentLineLength) {
            currentLine = nextLine();
            if (currentLine == null) {
                return null;
            }

            currentLineLength = currentLine.length();

            // Skip any leading whitespace.
            currentIndex = 0;
            while (currentIndex < currentLineLength &&
                    Character.isWhitespace(currentLine.charAt(currentIndex))) {
                currentIndex++;
            }

            // Remember any leading comments.
            if (currentIndex < currentLineLength &&
                    isComment(currentLine.charAt(currentIndex))) {
                // Remember the comments.
                String comment = currentLine.substring(currentIndex + 1);
                currentComments = currentComments == null ?
                        comment :
                        currentComments + '\n' + comment;

                // Skip the comments.
                currentIndex = currentLineLength;
            }
        }

        // Find the word starting at the current index.
        int startIndex = currentIndex;
        int endIndex;

        char startChar = currentLine.charAt(startIndex);

        if (isQuote(startChar)) {
            // The next word is starting with a quote character.
            // Skip the opening quote.
            startIndex++;

            // The next word is a quoted character string.
            // Find the closing quote.
            do {
                currentIndex++;

                if (currentIndex == currentLineLength) {
                    currentWord = currentLine.substring(startIndex - 1, currentIndex);
                    throw new IOException("Missing closing quote for " + locationDescription());
                }
            }
            while (currentLine.charAt(currentIndex) != startChar);

            endIndex = currentIndex++;
        } else if (isDelimiter(startChar)) {
            // The next word is a single delimiting character.
            endIndex = ++currentIndex;
        } else {
            // The next word is a simple character string.
            // Find the end of the line, the first delimiter, or the first
            // white space.
            while (currentIndex < currentLineLength) {
                char currentCharacter = currentLine.charAt(currentIndex);
                if (isNonStartDelimiter(currentCharacter) ||
                        Character.isWhitespace(currentCharacter) ||
                        isComment(currentCharacter)) {
                    break;
                }

                currentIndex++;
            }

            endIndex = currentIndex;
        }

        // Remember and return the parsed word.
        currentWord = currentLine.substring(startIndex, endIndex);

        if (currentIndex == currentLineLength && !";".equals(currentWord)) {
            // auto append a separator at end of line'
            stageWords.push(";");
        }
        return currentWord;
    }


    /**
     * Returns the comments collected before returning the last word.
     * Starts collecting new comments.
     *
     * @return the collected comments, or <code>null</code> if there weren't any.
     */
    public String lastComments() throws IOException {
        String comments = currentComments;
        currentComments = null;
        return comments;
    }

    public String peekCurrentWord() {
        return stageWords.isEmpty() ? currentWord : stageWords.peek();
    }

    public void pushBack(String word) {
        stageWords.push(word);
    }

    /**
     * Constructs a readable description of the current position in this
     * WordReader and its included WordReader objects.
     *
     * @return the description.
     */
    public String locationDescription() {
        String currentWord = peekCurrentWord();
        return (currentWord == null ?
                "end of " :
                "'" + currentWord + "' in ") +
                lineLocationDescription();
    }


    // Small utility methods.

    private boolean isComment(char character) {
        return character == COMMENT_CHARACTER;
    }


    private boolean isDelimiter(char character) {
        return isStartDelimiter(character) || isNonStartDelimiter(character);
    }


    private boolean isStartDelimiter(char character) {
        return character == '@';
    }


    private boolean isNonStartDelimiter(char character) {
        return character == '{' ||
                character == '}' ||
                character == '(' ||
                character == ')' ||
                character == ',' ||
                character == ';' ||
                character == '=' ||
                character == '[' ||
                character == ']' ||
                character == '+' ||
                character == '-' ||
                character == '*' ||
                character == '/' ||
                character == File.pathSeparatorChar;
    }


    private boolean isQuote(char character) {
        return character == '\'' ||
                character == '"';
    }


    /**
     * Reads a line from this WordReader, or from one of its active included
     * WordReader objects.
     *
     * @return the read line.
     */
    protected String nextLine() throws IOException {
        return reader.readLine();
    }

    /**
     * Returns a readable description of the current WordReader position.
     *
     * @return the description.
     */
    protected String lineLocationDescription() {
        return "line " + reader.getLineNumber() + " of " + description;
    }


    public void close() throws IOException {
        if (reader != null) {
            reader.close();
        }
    }
}
